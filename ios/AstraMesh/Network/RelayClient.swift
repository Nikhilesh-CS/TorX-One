import Foundation
import Sodium

/// WebSocket relay client implementing the TorX One wire protocol.
/// Uses URLSessionWebSocketTask (built-in iOS 13+).
class RelayClient: ObservableObject {
    
    /// Connection state published to SwiftUI views.
    @Published var isConnected: Bool = false
    
    /// Callback invoked when an incoming message is received.
    /// Parameters: (fromSigningKeyHex, ciphertextBytes, nonceBytes, signatureBytes)
    var onMessageReceived: ((String, Bytes, Bytes, Bytes) -> Void)?
    
    private var webSocketTask: URLSessionWebSocketTask?
    private var session: URLSession?
    private var identity: Identity?
    private var serverURL: URL?
    
    // Reconnection state
    private var shouldReconnect = true
    private var reconnectAttempt = 0
    private let maxReconnectDelay: TimeInterval = 30
    
    // MARK: - Connection
    
    /// Connect to the relay server and begin the authentication handshake.
    func connect(identity: Identity, serverURL: String = "ws://localhost:3000") {
        self.identity = identity
        self.serverURL = URL(string: serverURL)
        self.shouldReconnect = true
        self.reconnectAttempt = 0
        
        performConnect()
    }
    
    /// Disconnect from the relay and stop reconnection.
    func disconnect() {
        shouldReconnect = false
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        DispatchQueue.main.async {
            self.isConnected = false
        }
    }
    
    private func performConnect() {
        guard let url = serverURL else { return }
        
        session = URLSession(configuration: .default)
        webSocketTask = session?.webSocketTask(with: url)
        webSocketTask?.resume()
        
        receiveMessage()
    }
    
    // MARK: - Receiving
    
    private func receiveMessage() {
        webSocketTask?.receive { [weak self] result in
            guard let self = self else { return }
            
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleTextMessage(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.handleTextMessage(text)
                    }
                @unknown default:
                    break
                }
                // Continue receiving
                self.receiveMessage()
                
            case .failure(let error):
                print("[RelayClient] WebSocket receive error: \(error)")
                DispatchQueue.main.async {
                    self.isConnected = false
                }
                self.attemptReconnect()
            }
        }
    }
    
    // MARK: - Message Handling
    
    private func handleTextMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else {
            return
        }
        
        switch type {
        case "challenge":
            handleChallenge(json)
        case "auth_ok":
            handleAuthOk()
        case "message":
            handleIncomingMessage(json)
        case "sent":
            // Message delivery confirmed — could trigger UI update
            if let id = json["id"] as? String {
                print("[RelayClient] Message sent confirmed: \(id)")
            }
        case "error":
            if let errorMsg = json["message"] as? String {
                print("[RelayClient] Server error: \(errorMsg)")
            }
        default:
            print("[RelayClient] Unknown message type: \(type)")
        }
    }
    
    /// Handle challenge: sign the nonce with Ed25519 secret key, send auth response.
    private func handleChallenge(_ json: [String: Any]) {
        guard let identity = identity,
              let nonceHex = json["nonce"] as? String else {
            return
        }
        
        let nonceBytes = decodeHex(nonceHex)
        guard nonceBytes.count == 32 else {
            print("[RelayClient] Invalid challenge nonce length: \(nonceBytes.count)")
            return
        }
        
        // Sign the raw nonce bytes with our Ed25519 signing secret key
        guard let signature = CryptoManager.shared.sign(
            data: nonceBytes,
            secretKey: identity.signingSecretKey
        ) else {
            print("[RelayClient] Failed to sign challenge nonce")
            return
        }
        
        // Send auth response
        let authMessage: [String: Any] = [
            "type": "auth",
            "publicKey": encodeHex(identity.signingPublicKey),
            "signature": encodeHex(signature)
        ]
        
        sendJSON(authMessage)
    }
    
    /// Handle auth_ok: mark as connected, reset reconnect counter.
    private func handleAuthOk() {
        reconnectAttempt = 0
        DispatchQueue.main.async {
            self.isConnected = true
        }
        print("[RelayClient] Authenticated successfully")
    }
    
    /// Handle incoming message: extract fields and emit via callback.
    private func handleIncomingMessage(_ json: [String: Any]) {
        guard let id = json["id"] as? String,
              let from = json["from"] as? String,
              let ciphertextHex = json["ciphertext"] as? String,
              let nonceHex = json["nonce"] as? String,
              let signatureHex = json["signature"] as? String else {
            return
        }
        
        let ciphertext = decodeHex(ciphertextHex)
        let nonce = decodeHex(nonceHex)
        let signature = decodeHex(signatureHex)
        
        // Send acknowledgment
        sendJSON(["type": "ack", "id": id])
        
        // Emit to callback
        onMessageReceived?(from, ciphertext, nonce, signature)
    }
    
    // MARK: - Sending
    
    /// Send an encrypted message to a recipient via the relay.
    func sendMessage(to recipientSigPubHex: String,
                     ciphertext: Bytes,
                     nonce: Bytes,
                     signature: Bytes) {
        let message: [String: Any] = [
            "type": "message",
            "to": recipientSigPubHex,
            "ciphertext": encodeHex(ciphertext),
            "nonce": encodeHex(nonce),
            "signature": encodeHex(signature)
        ]
        
        sendJSON(message)
    }
    
    /// Serialize a dictionary to JSON and send over WebSocket.
    private func sendJSON(_ dict: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let text = String(data: data, encoding: .utf8) else {
            return
        }
        
        webSocketTask?.send(.string(text)) { error in
            if let error = error {
                print("[RelayClient] Send error: \(error)")
            }
        }
    }
    
    // MARK: - Reconnection
    
    private func attemptReconnect() {
        guard shouldReconnect else { return }
        
        reconnectAttempt += 1
        let delay = min(pow(2.0, Double(reconnectAttempt)), maxReconnectDelay)
        
        print("[RelayClient] Reconnecting in \(delay)s (attempt \(reconnectAttempt))")
        
        DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self = self, self.shouldReconnect else { return }
            self.performConnect()
        }
    }
}
