import Foundation
import Security

/// Simple Keychain wrapper for storing and retrieving Data blobs by key.
final class KeychainManager {
    
    static let shared = KeychainManager()
    
    private let service = "com.torxone.ios"
    
    private init() {}
    
    /// Save data to the Keychain under the given key.
    /// Overwrites any existing value for the key.
    @discardableResult
    func save(key: String, data: Data) -> Bool {
        // Delete any existing item first
        delete(key: key)
        
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        
        let status = SecItemAdd(query as CFDictionary, nil)
        return status == errSecSuccess
    }
    
    /// Retrieve data from the Keychain for the given key.
    func load(key: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecReturnData as String: true
        ]
        
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }
    
    /// Delete data from the Keychain for the given key.
    @discardableResult
    func delete(key: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }
    
    /// Check if a value exists for the given key.
    func exists(key: String) -> Bool {
        return load(key: key) != nil
    }
}
