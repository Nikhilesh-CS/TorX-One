import Foundation
import GRDB

/// Manages the local SQLite database for contacts and messages using GRDB.
final class DatabaseManager {
    
    static let shared = DatabaseManager()
    
    private var dbQueue: DatabaseQueue?
    
    private init() {
        do {
            try setupDatabase()
        } catch {
            print("[DatabaseManager] Failed to initialize database: \(error)")
        }
    }
    
    // MARK: - Setup
    
    private func setupDatabase() throws {
        // Store database in the app's Documents directory
        let fileManager = FileManager.default
        let documentsURL = try fileManager.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dbURL = documentsURL.appendingPathComponent("torxone.sqlite")
        
        dbQueue = try DatabaseQueue(path: dbURL.path)
        
        try dbQueue?.write { db in
            // Create contacts table
            try db.execute(sql: """
                CREATE TABLE IF NOT EXISTS contacts (
                    signingPublicKey TEXT PRIMARY KEY,
                    encryptionPublicKey TEXT NOT NULL,
                    name TEXT NOT NULL,
                    onionAddress TEXT NOT NULL DEFAULT ''
                )
            """)
            
            let columns = try Row.fetchAll(db, sql: "PRAGMA table_info(contacts)")
            let hasOnionAddress = columns.contains { row in
                (row["name"] as String) == "onionAddress"
            }
            if !hasOnionAddress {
                try db.execute(sql: """
                    ALTER TABLE contacts
                    ADD COLUMN onionAddress TEXT NOT NULL DEFAULT ''
                """)
            }
            
            // Create messages table
            try db.execute(sql: """
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    contactKey TEXT NOT NULL,
                    text TEXT NOT NULL,
                    timestamp REAL NOT NULL,
                    direction TEXT NOT NULL,
                    FOREIGN KEY (contactKey) REFERENCES contacts(signingPublicKey)
                )
            """)
            
            // Index for faster message lookups by contact
            try db.execute(sql: """
                CREATE INDEX IF NOT EXISTS idx_messages_contact 
                ON messages(contactKey, timestamp)
            """)
        }
    }
    
    // MARK: - Contacts
    
    /// Save or update a contact.
    func saveContact(_ contact: Contact) throws {
        try dbQueue?.write { db in
            try db.execute(
                sql: """
                    INSERT OR REPLACE INTO contacts (signingPublicKey, encryptionPublicKey, name, onionAddress)
                    VALUES (?, ?, ?, ?)
                """,
                arguments: [contact.signingPublicKey, contact.encryptionPublicKey, contact.name, contact.onionAddress]
            )
        }
    }
    
    /// Retrieve all contacts.
    func getContacts() throws -> [Contact] {
        guard let dbQueue = dbQueue else { return [] }
        
        return try dbQueue.read { db in
            let rows = try Row.fetchAll(db, sql: "SELECT * FROM contacts ORDER BY name")
            return rows.map { row in
                Contact(
                    signingPublicKey: row["signingPublicKey"],
                    encryptionPublicKey: row["encryptionPublicKey"],
                    name: row["name"],
                    onionAddress: row["onionAddress"]
                )
            }
        }
    }
    
    /// Find a contact by their signing public key.
    func getContact(bySigningKey key: String) throws -> Contact? {
        guard let dbQueue = dbQueue else { return nil }
        
        return try dbQueue.read { db in
            guard let row = try Row.fetchOne(
                db,
                sql: "SELECT * FROM contacts WHERE signingPublicKey = ?",
                arguments: [key]
            ) else {
                return nil
            }
            return Contact(
                signingPublicKey: row["signingPublicKey"],
                encryptionPublicKey: row["encryptionPublicKey"],
                name: row["name"],
                onionAddress: row["onionAddress"]
            )
        }
    }
    
    /// Delete a contact and their messages.
    func deleteContact(signingPublicKey: String) throws {
        try dbQueue?.write { db in
            try db.execute(
                sql: "DELETE FROM messages WHERE contactKey = ?",
                arguments: [signingPublicKey]
            )
            try db.execute(
                sql: "DELETE FROM contacts WHERE signingPublicKey = ?",
                arguments: [signingPublicKey]
            )
        }
    }
    
    // MARK: - Messages
    
    /// Save a message and return its assigned ID.
    @discardableResult
    func saveMessage(_ message: Message) throws -> Int64 {
        guard let dbQueue = dbQueue else { return -1 }
        
        return try dbQueue.write { db in
            try db.execute(
                sql: """
                    INSERT INTO messages (contactKey, text, timestamp, direction)
                    VALUES (?, ?, ?, ?)
                """,
                arguments: [
                    message.contactKey,
                    message.text,
                    message.timestamp,
                    message.direction.rawValue
                ]
            )
            return db.lastInsertedRowID
        }
    }
    
    /// Retrieve all messages for a specific contact, ordered by timestamp.
    func getMessages(forContact contactKey: String) throws -> [Message] {
        guard let dbQueue = dbQueue else { return [] }
        
        return try dbQueue.read { db in
            let rows = try Row.fetchAll(
                db,
                sql: "SELECT * FROM messages WHERE contactKey = ? ORDER BY timestamp ASC",
                arguments: [contactKey]
            )
            return rows.map { row in
                Message(
                    id: row["id"],
                    contactKey: row["contactKey"],
                    text: row["text"],
                    timestamp: row["timestamp"],
                    direction: MessageDirection(rawValue: row["direction"]) ?? .received
                )
            }
        }
    }
    
    /// Get the last message for a contact (for the chat list preview).
    func getLastMessage(forContact contactKey: String) throws -> Message? {
        guard let dbQueue = dbQueue else { return nil }
        
        return try dbQueue.read { db in
            guard let row = try Row.fetchOne(
                db,
                sql: "SELECT * FROM messages WHERE contactKey = ? ORDER BY timestamp DESC LIMIT 1",
                arguments: [contactKey]
            ) else {
                return nil
            }
            return Message(
                id: row["id"],
                contactKey: row["contactKey"],
                text: row["text"],
                timestamp: row["timestamp"],
                direction: MessageDirection(rawValue: row["direction"]) ?? .received
            )
        }
    }
}
