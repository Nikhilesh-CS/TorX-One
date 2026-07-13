import Foundation
import Sodium
import Combine

/// Central view model wiring together crypto, networking, and storage.
@MainActor
class ChatViewModel: ObservableObject {
    
    // MARK: - Published State
    
    @Published var identity: Identity?
    @Published var contacts: [Contact] = []
    @Published var messages: [Message] = []
    @Published var activeContact: Contact?
    @Published var isConnected: Bool = false
    @Published var error: String?
    @Published var isUnlocked: Bool = false
    @Published var lastMessages: [String: Message] = [:]
    
    // MARK: - Dependencies
    
    private let crypto = CryptoManager.shared
    private let relay = RelayClient()
    private let db = DatabaseManager.shared
    private let keychain = KeychainManager.shared
    
    private var cancellables = Set<AnyCancellable>()
    
    // MARK: - Constants
    
    private let identityKeychainKey = "torxone.identity"
    private let relayURL = "ws://localhost:3000"
    
    init() {
        // Observe relay connection state
        relay.$isConnected
            .receive(on: DispatchQueue.main)
            .assign(to: &$isConnected)
        
        // Set up incoming message handler
        relay.onMessageReceived = { [weak self] from, ciphertext, nonce, signature in
            Task { @MainActor [weak self] in
                self?.handleIncomingMessage(from: from, ciphertext: ciphertext, nonce: nonce, signature: signature)
            }
        }
        
        // Check if identity exists in keychain
        if keychain.exists(key: identityKeychainKey) {
            // Identity exists but needs passphrase to unlock
        }
    }
    
    // MARK: - Identity Management
    
    /// Whether an identity has been created (may or may not be unlocked).
    var hasIdentity: Bool {
        return keychain.exists(key: identityKeychainKey)
    }
    
    /// Create a new identity, encrypt it with the passphrase, and store it.
    func createIdentity(name: String, passphrase: String) {
        guard !name.isEmpty, !passphrase.isEmpty else {
            error = "Name and passphrase are required."
            return
        }
        
        guard let newIdentity = crypto.generateIdentity(name: name) else {
            error = "Failed to generate identity."
            return
        }
        
        // Encrypt and store the identity
        guard let blob = crypto.encryptIdentity(newIdentity, passphrase: passphrase),
              let blobData = try? JSONSerialization.data(withJSONObject: blob) else {
            error = "Failed to encrypt identity."
            return
        }
        
        keychain.save(key: identityKeychainKey, data: blobData)
        
        self.identity = newIdentity
        self.isUnlocked = true
        self.error = nil
        
        // Connect to relay
        connectToRelay()
        
        // Load contacts
        loadContacts()
    }
    
    /// Unlock an existing identity with a passphrase.
    func unlock(passphrase: String) {
        guard let blobData = keychain.load(key: identityKeychainKey),
              let blob = try? JSONSerialization.jsonObject(with: blobData) as? [String: String] else {
            error = "No identity found."
            return
        }
        
        guard let decryptedIdentity = crypto.decryptIdentity(blob: blob, passphrase: passphrase) else {
            error = "Wrong passphrase."
            return
        }
        
        self.identity = decryptedIdentity
        self.isUnlocked = true
        self.error = nil
        
        // Connect to relay
        connectToRelay()
        
        // Load contacts
        loadContacts()
    }
    
    // MARK: - Relay Connection
    
    private func connectToRelay() {
        guard let identity = identity else { return }
        relay.connect(identity: identity, serverURL: relayURL)
    }
    
    // MARK: - Contacts
    
    /// Load all contacts from the database.
    func loadContacts() {
        do {
            contacts = try db.getContacts()
            // Load last messages for each contact
            for contact in contacts {
                if let last = try db.getLastMessage(forContact: contact.signingPublicKey) {
                    lastMessages[contact.signingPublicKey] = last
                }
            }
        } catch {
            self.error = "Failed to load contacts: \(error.localizedDescription)"
        }
    }
    
    /// Add a contact from a contact string (astra:<base64>).
    func addContact(from contactString: String) {
        guard let parsed = crypto.parseContactString(contactString) else {
            error = "Invalid contact string."
            return
        }
        
        let contact = Contact(
            signingPublicKey: encodeHex(parsed.sigPub),
            encryptionPublicKey: encodeHex(parsed.encPub),
            name: parsed.name,
            onionAddress: parsed.onionAddress ?? ""
        )
        
        // Don't add ourselves
        if let identity = identity, contact.signingPublicKey == identity.signingPublicKeyHex {
            error = "You cannot add yourself as a contact."
            return
        }
        
        do {
            try db.saveContact(contact)
            loadContacts()
            self.error = nil
        } catch {
            self.error = "Failed to save contact: \(error.localizedDescription)"
        }
    }
    
    /// Get the shareable contact string for the current identity.
    func getMyContactString() -> String? {
        guard let identity = identity else { return nil }
        return crypto.createContactString(identity: identity)
    }
    
    // MARK: - Chat
    
    /// Select a contact and load their message history.
    func selectContact(_ contact: Contact) {
        activeContact = contact
        loadMessages(for: contact)
    }
    
    /// Load messages for the given contact.
    func loadMessages(for contact: Contact) {
        do {
            messages = try db.getMessages(forContact: contact.signingPublicKey)
        } catch {
            self.error = "Failed to load messages: \(error.localizedDescription)"
        }
    }
    
    /// Send an encrypted, signed message to the active contact.
    func sendMessage(text: String) {
        guard let identity = identity,
              let contact = activeContact,
              !text.isEmpty else {
            return
        }
        
        // 1. Encrypt: crypto_box(plaintext, recipientEncPub, senderEncSec)
        guard let encrypted = crypto.encryptMessage(
            plaintext: text,
            recipientEncPub: contact.encryptionPublicKeyBytes,
            senderEncSec: identity.encryptionSecretKey
        ) else {
            error = "Failed to encrypt message."
            return
        }
        
        // 2. Sign: crypto_sign_detached(ciphertext || nonce, signingSecretKey)
        let dataToSign = encrypted.ciphertext + encrypted.nonce
        guard let signature = crypto.sign(data: dataToSign, secretKey: identity.signingSecretKey) else {
            error = "Failed to sign message."
            return
        }
        
        // 3. Send via relay
        relay.sendMessage(
            to: contact.signingPublicKey,
            ciphertext: encrypted.ciphertext,
            nonce: encrypted.nonce,
            signature: signature
        )
        
        // 4. Save to local DB
        let message = Message(
            contactKey: contact.signingPublicKey,
            text: text,
            timestamp: Date().timeIntervalSince1970,
            direction: .sent
        )
        
        do {
            try db.saveMessage(message)
            loadMessages(for: contact)
            lastMessages[contact.signingPublicKey] = message
        } catch {
            self.error = "Failed to save message: \(error.localizedDescription)"
        }
    }
    
    // MARK: - Incoming Messages
    
    /// Handle an incoming message from the relay.
    private func handleIncomingMessage(from senderSigPubHex: String,
                                       ciphertext: Bytes,
                                       nonce: Bytes,
                                       signature: Bytes) {
        // 1. Look up the sender in our contacts
        guard let contact = contacts.first(where: { $0.signingPublicKey == senderSigPubHex }) else {
            print("[ChatViewModel] Received message from unknown sender: \(senderSigPubHex)")
            return
        }
        
        // 2. Verify signature: crypto_sign_verify(ciphertext || nonce, signature, senderSigPub)
        let dataToVerify = ciphertext + nonce
        guard crypto.verify(
            data: dataToVerify,
            signature: signature,
            publicKey: contact.signingPublicKeyBytes
        ) else {
            print("[ChatViewModel] Signature verification failed for message from \(contact.name)")
            return
        }
        
        // 3. Decrypt: crypto_box_open(ciphertext, nonce, senderEncPub, recipientEncSec)
        guard let identity = identity,
              let plaintext = crypto.decryptMessage(
                  ciphertext: ciphertext,
                  nonce: nonce,
                  senderEncPub: contact.encryptionPublicKeyBytes,
                  recipientEncSec: identity.encryptionSecretKey
              ) else {
            print("[ChatViewModel] Failed to decrypt message from \(contact.name)")
            return
        }
        
        // 4. Save to local DB
        let message = Message(
            contactKey: contact.signingPublicKey,
            text: plaintext,
            timestamp: Date().timeIntervalSince1970,
            direction: .received
        )
        
        do {
            try db.saveMessage(message)
            lastMessages[contact.signingPublicKey] = message
            
            // If we're viewing this contact's chat, reload messages
            if activeContact?.signingPublicKey == contact.signingPublicKey {
                loadMessages(for: contact)
            }
        } catch {
            self.error = "Failed to save incoming message: \(error.localizedDescription)"
        }
    }
    
    // MARK: - Cleanup
    
    /// Delete the identity and all data.
    func deleteIdentity() {
        relay.disconnect()
        keychain.delete(key: identityKeychainKey)
        identity = nil
        isUnlocked = false
        contacts = []
        messages = []
        activeContact = nil
        lastMessages = [:]
    }
}
