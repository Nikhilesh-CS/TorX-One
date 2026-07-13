import Foundation
import Sodium

/// A user's full identity containing both encryption and signing keypairs.
/// All keys are stored as raw byte arrays (`Bytes` = `[UInt8]`).
struct Identity: Codable {
    let name: String
    let encryptionPublicKey: Bytes
    let encryptionSecretKey: Bytes
    let signingPublicKey: Bytes
    let signingSecretKey: Bytes
    
    /// The signing public key as a lowercase hex string — used as the user's stable TorX One address.
    var signingPublicKeyHex: String {
        return encodeHex(signingPublicKey)
    }
    
    /// The encryption public key as a lowercase hex string.
    var encryptionPublicKeyHex: String {
        return encodeHex(encryptionPublicKey)
    }
    
    // MARK: - Codable
    
    enum CodingKeys: String, CodingKey {
        case name
        case encryptionPublicKey = "encPub"
        case encryptionSecretKey = "encSec"
        case signingPublicKey = "sigPub"
        case signingSecretKey = "sigSec"
    }
    
    init(name: String,
         encryptionPublicKey: Bytes,
         encryptionSecretKey: Bytes,
         signingPublicKey: Bytes,
         signingSecretKey: Bytes) {
        self.name = name
        self.encryptionPublicKey = encryptionPublicKey
        self.encryptionSecretKey = encryptionSecretKey
        self.signingPublicKey = signingPublicKey
        self.signingSecretKey = signingSecretKey
    }
    
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.name = try container.decode(String.self, forKey: .name)
        let encPubHex = try container.decode(String.self, forKey: .encryptionPublicKey)
        let encSecHex = try container.decode(String.self, forKey: .encryptionSecretKey)
        let sigPubHex = try container.decode(String.self, forKey: .signingPublicKey)
        let sigSecHex = try container.decode(String.self, forKey: .signingSecretKey)
        self.encryptionPublicKey = decodeHex(encPubHex)
        self.encryptionSecretKey = decodeHex(encSecHex)
        self.signingPublicKey = decodeHex(sigPubHex)
        self.signingSecretKey = decodeHex(sigSecHex)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(name, forKey: .name)
        try container.encode(encodeHex(encryptionPublicKey), forKey: .encryptionPublicKey)
        try container.encode(encodeHex(encryptionSecretKey), forKey: .encryptionSecretKey)
        try container.encode(encodeHex(signingPublicKey), forKey: .signingPublicKey)
        try container.encode(encodeHex(signingSecretKey), forKey: .signingSecretKey)
    }
}

// MARK: - Hex Utilities (global, used throughout the app)

/// Encode raw bytes to a lowercase hex string.
func encodeHex(_ bytes: Bytes) -> String {
    return bytes.map { String(format: "%02x", $0) }.joined()
}

/// Decode a hex string to raw bytes. Returns empty array on invalid input.
func decodeHex(_ hex: String) -> Bytes {
    let cleanHex = hex.lowercased()
    var bytes = Bytes()
    bytes.reserveCapacity(cleanHex.count / 2)
    var index = cleanHex.startIndex
    while index < cleanHex.endIndex {
        let nextIndex = cleanHex.index(index, offsetBy: 2)
        guard nextIndex <= cleanHex.endIndex,
              let byte = UInt8(cleanHex[index..<nextIndex], radix: 16) else {
            return []
        }
        bytes.append(byte)
        index = nextIndex
    }
    return bytes
}
