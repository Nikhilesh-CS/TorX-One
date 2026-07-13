import Foundation
import Sodium

/// Singleton manager for all cryptographic operations using libsodium.
/// Implements the TorX One protocol spec exactly.
final class CryptoManager {
    
    static let shared = CryptoManager()
    
    let sodium = Sodium()
    
    private init() {}
    
    // MARK: - Identity Generation
    
    /// Generate a new identity with X25519 encryption and Ed25519 signing keypairs.
    func generateIdentity(name: String) -> Identity? {
        guard let encKeyPair = sodium.box.keyPair(),
              let sigKeyPair = sodium.sign.keyPair() else {
            return nil
        }
        return Identity(
            name: name,
            encryptionPublicKey: encKeyPair.publicKey,
            encryptionSecretKey: encKeyPair.secretKey,
            signingPublicKey: sigKeyPair.publicKey,
            signingSecretKey: sigKeyPair.secretKey
        )
    }
    
    // MARK: - Message Encryption (crypto_box)
    
    /// Encrypt plaintext using crypto_box (X25519 + XSalsa20-Poly1305).
    /// Returns (ciphertext, nonce) or nil on failure.
    func encryptMessage(plaintext: String,
                        recipientEncPub: Bytes,
                        senderEncSec: Bytes) -> (ciphertext: Bytes, nonce: Bytes)? {
        let nonce = sodium.box.nonce()
        guard let plaintextBytes = plaintext.data(using: .utf8).map({ Bytes($0) }),
              let cipher = sodium.box.seal(
                  message: plaintextBytes,
                  recipientPublicKey: recipientEncPub,
                  senderSecretKey: senderEncSec,
                  nonce: nonce
              ) else {
            return nil
        }
        return (ciphertext: cipher, nonce: nonce)
    }
    
    /// Decrypt ciphertext using crypto_box_open.
    /// Returns the plaintext string or nil on failure.
    func decryptMessage(ciphertext: Bytes,
                        nonce: Bytes,
                        senderEncPub: Bytes,
                        recipientEncSec: Bytes) -> String? {
        guard let decrypted = sodium.box.open(
                  authenticatedCipherText: ciphertext,
                  senderPublicKey: senderEncPub,
                  recipientSecretKey: recipientEncSec,
                  nonce: nonce
              ) else {
            return nil
        }
        return String(bytes: decrypted, encoding: .utf8)
    }
    
    // MARK: - Signing (Ed25519)
    
    /// Create a detached Ed25519 signature over data.
    func sign(data: Bytes, secretKey: Bytes) -> Bytes? {
        return sodium.sign.signature(message: data, secretKey: secretKey)
    }
    
    /// Verify a detached Ed25519 signature.
    func verify(data: Bytes, signature: Bytes, publicKey: Bytes) -> Bool {
        return sodium.sign.verify(message: data, publicKey: publicKey, signature: signature)
    }
    
    // MARK: - Contact String
    
    /// Create a shareable contact string: `astra:<base64(JSON({"n","e","s","o?"}))>`
    func createContactString(identity: Identity, onionAddress: String? = nil) -> String {
        var payload: [String: String] = [
            "n": identity.name,
            "e": encodeHex(identity.encryptionPublicKey),
            "s": encodeHex(identity.signingPublicKey)
        ]
        if let onionAddress, !onionAddress.isEmpty {
            payload["o"] = onionAddress.lowercased()
        }
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else {
            return ""
        }

        let base64Str = jsonData.base64EncodedString()
        return "astra:\(base64Str)"
    }
    
    struct ParsedContact {
        let name: String
        let encPub: Bytes
        let sigPub: Bytes
        let onionAddress: String?
    }
    
    /// Parse a contact string back into a Tor-aware contact payload.
    func parseContactString(_ contactString: String) -> ParsedContact? {
        let trimmed = contactString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("astra:") else { return nil }
        
        let base64Part = String(trimmed.dropFirst("astra:".count))
        guard let jsonData = Data(base64Encoded: base64Part) else { return nil }
        
        guard let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: String],
              let name = json["n"],
              let encHex = json["e"],
              let sigHex = json["s"] else {
            return nil
        }
        
        let encPub = decodeHex(encHex)
        let sigPub = decodeHex(sigHex)
        let onionAddress = json["o"]?.lowercased()
        
        // Validate key sizes: 32 bytes each
        guard encPub.count == 32, sigPub.count == 32 else { return nil }
        
        return ParsedContact(name: name, encPub: encPub, sigPub: sigPub, onionAddress: onionAddress)
    }
    
    // MARK: - Identity Encryption at Rest (Section 5 of Protocol)
    
    /// Encrypt an identity with a passphrase using Argon2id + crypto_secretbox.
    /// Returns a JSON-serializable dictionary with salt, nonce, data (all hex).
    func encryptIdentity(_ identity: Identity, passphrase: String) -> [String: String]? {
        // Generate salt (16 bytes)
        let salt = sodium.randomBytes.buf(length: 16)!
        
        // Derive key using Argon2id
        guard let passphraseBytes = passphrase.data(using: .utf8).map({ Bytes($0) }),
              let key = sodium.pwHash.hash(
                  outputLength: 32,
                  passwd: passphraseBytes,
                  salt: salt,
                  opsLimit: sodium.pwHash.OpsLimitInteractive,
                  memLimit: sodium.pwHash.MemLimitInteractive,
                  alg: .Argon2ID13
              ) else {
            return nil
        }
        
        // Serialize identity to JSON (all keys as hex)
        let identityPayload: [String: String] = [
            "name": identity.name,
            "encPub": encodeHex(identity.encryptionPublicKey),
            "encSec": encodeHex(identity.encryptionSecretKey),
            "sigPub": encodeHex(identity.signingPublicKey),
            "sigSec": encodeHex(identity.signingSecretKey)
        ]
        
        guard let jsonData = try? JSONSerialization.data(withJSONObject: identityPayload),
              let encrypted = sodium.secretBox.seal(
                  message: Bytes(jsonData),
                  secretKey: key
              ) else {
            return nil
        }
        
        // secretBox.seal returns nonce + ciphertext combined
        // We need to separate them: first 24 bytes = nonce, rest = ciphertext
        let nonce = Bytes(encrypted.prefix(sodium.secretBox.NonceBytes))
        let ciphertext = Bytes(encrypted.suffix(from: sodium.secretBox.NonceBytes))
        
        return [
            "salt": encodeHex(salt),
            "nonce": encodeHex(nonce),
            "data": encodeHex(ciphertext)
        ]
    }
    
    /// Decrypt an identity from its encrypted blob using a passphrase.
    func decryptIdentity(blob: [String: String], passphrase: String) -> Identity? {
        guard let saltHex = blob["salt"],
              let nonceHex = blob["nonce"],
              let dataHex = blob["data"] else {
            return nil
        }
        
        let salt = decodeHex(saltHex)
        let nonce = decodeHex(nonceHex)
        let ciphertext = decodeHex(dataHex)
        
        guard salt.count == 16, nonce.count == 24 else { return nil }
        
        // Derive key
        guard let passphraseBytes = passphrase.data(using: .utf8).map({ Bytes($0) }),
              let key = sodium.pwHash.hash(
                  outputLength: 32,
                  passwd: passphraseBytes,
                  salt: salt,
                  opsLimit: sodium.pwHash.OpsLimitInteractive,
                  memLimit: sodium.pwHash.MemLimitInteractive,
                  alg: .Argon2ID13
              ) else {
            return nil
        }
        
        // Decrypt — secretBox.open expects nonce + ciphertext combined
        let combined = nonce + ciphertext
        guard let decrypted = sodium.secretBox.open(
                  nonceAndAuthenticatedCipherText: combined,
                  secretKey: key
              ) else {
            return nil
        }
        
        // Parse JSON
        guard let json = try? JSONSerialization.jsonObject(with: Data(decrypted)) as? [String: String],
              let name = json["name"],
              let encPubHex = json["encPub"],
              let encSecHex = json["encSec"],
              let sigPubHex = json["sigPub"],
              let sigSecHex = json["sigSec"] else {
            return nil
        }
        
        return Identity(
            name: name,
            encryptionPublicKey: decodeHex(encPubHex),
            encryptionSecretKey: decodeHex(encSecHex),
            signingPublicKey: decodeHex(sigPubHex),
            signingSecretKey: decodeHex(sigSecHex)
        )
    }
}
