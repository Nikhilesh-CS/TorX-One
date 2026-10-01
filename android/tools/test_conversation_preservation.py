"""Prevent destructive parent-row replacement and reproduce its FK effect."""
import json
import sqlite3
import unittest
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1]


class ConversationPreservationTest(unittest.TestCase):
    def test_conversation_writes_use_room_upsert(self):
        source = (ANDROID / "app/src/main/java/com/torxone/app/data/dao/Daos.kt").read_text(encoding="utf-8")
        dao = source.split("interface ConversationDao {", 1)[1].split("interface MessageDao {", 1)[0]
        self.assertNotIn("OnConflictStrategy.REPLACE", dao)
        self.assertRegex(dao, r"@Upsert\s+suspend fun upsert\(conversation: ConversationEntity\)")
        self.assertRegex(dao, r"@Upsert\s+suspend fun upsertPreservingMessages\(conversation: ConversationEntity\)")

    def test_real_schema_parent_replace_deletes_history_but_update_retains_it(self):
        schema = json.loads((ANDROID / "app/schemas/com.torxone.app.data.TorXDatabase/20.json").read_text(encoding="utf-8"))
        tables = {entity["tableName"]: entity for entity in schema["database"]["entities"]}
        with sqlite3.connect(":memory:") as db:
            db.execute("PRAGMA foreign_keys=ON")
            for name in ["conversations", "messages", "conversation_drafts"]:
                db.execute(tables[name]["createSql"].replace("${TABLE_NAME}", name))
            conversation = ("chat", "DIRECT", "Original", 0, 0, 0, 0, 1)
            columns = "conversationId,type,title,unread_count,is_pinned,is_archived,manually_unread,created_at"
            db.execute(f"INSERT INTO conversations({columns}) VALUES(?,?,?,?,?,?,?,?)", conversation)
            def children():
                db.execute("INSERT INTO messages(logical_message_id,conversation_id,sender_id,type,body,direction,status,created_at,edit_version) VALUES('message','chat','peer','TEXT','fixture','INCOMING','DELIVERED',1,0)")
                db.execute("INSERT INTO conversation_drafts(conversationId,text,replyToMessageId,updatedAt) VALUES('chat','draft','message',1)")
            children()
            db.execute(f"INSERT OR REPLACE INTO conversations({columns}) VALUES(?,?,?,?,?,?,?,?)", conversation)
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM messages").fetchone()[0])
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM conversation_drafts").fetchone()[0])
            children()
            db.execute("UPDATE conversations SET title='Updated' WHERE conversationId='chat'")
            self.assertEqual("fixture", db.execute("SELECT body FROM messages").fetchone()[0])
            self.assertEqual("draft", db.execute("SELECT text FROM conversation_drafts").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
