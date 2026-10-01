"""Execute production Room expiry queries on SQLite; Android lifecycle tests remain separate."""
import json
import re
import sqlite3
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/torxone/app"
SOURCE = (APP / "privacy/SecurityPolicyDao.kt").read_text(encoding="utf-8")


def query(method):
    found = re.search(r'@Query\((""".*?"""|"[^"\n]*")\)\s*suspend fun ' + method + r'\(', SOURCE, re.DOTALL)
    if not found:
        raise AssertionError(f"Review production query extraction: {method}")
    value = found[1]
    return value[3:-3] if value.startswith('"""') else value[1:-1]


def create(path=":memory:"):
    db = sqlite3.connect(path)
    schema = json.loads((ROOT / "app/schemas/com.torxone.app.data.TorXDatabase/19.json").read_text(encoding="utf-8"))["database"]
    for table in schema["entities"]:
        db.execute(table["createSql"].replace("${TABLE_NAME}", table["tableName"]))
        for index in table.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", table["tableName"]))
    migration = (APP / "privacy/MediaFileSchema.kt").read_text(encoding="utf-8")
    statements = re.findall(r'db\.execSQL\("([^"\n]+)"\)', migration)
    if len(statements) != 1:
        raise AssertionError("Review migration extraction")
    db.execute(statements[0])
    db.execute("INSERT INTO conversations(conversationId,type,title,unread_count,is_pinned,is_archived,manually_unread,created_at) VALUES('chat','DIRECT','Fixture',0,0,0,0,1)")
    return db


def message(db, identifier, expiry, deleted=None):
    db.execute("INSERT INTO messages(logical_message_id,conversation_id,sender_id,type,body,direction,status,created_at,edit_version,expires_at,deleted_at) VALUES(?,'chat','peer','FILE',?,'INCOMING','DELIVERED',1,0,?,?)",
               (identifier, None if deleted else "private", expiry, deleted))


class ExpiryQueriesTest(unittest.TestCase):
    def test_deleted_attachment_is_due_and_cleaned_tombstone_is_not_due(self):
        db = create()
        try:
            message(db, "expired", 100, 50)
            message(db, "future", 300)
            message(db, "normal", None)
            db.execute("INSERT INTO media(media_id,message_id,conversation_id,media_type,mime_type,file_name,file_size,encrypted_sha256,media_key,status,transfer_progress,created_at) VALUES('media','expired','chat','DOCUMENT','text/plain','private.txt',1,'hash',X'01','COMPLETE',1,1)")
            due = db.execute(query("due"), {"now": 200}).fetchall()
            self.assertEqual(["expired"], [row[0] for row in due])
            db.execute("DELETE FROM media WHERE media_id='media'")
            db.execute(query("tombstoneMessage"), {"id": "expired", "now": 200})
            self.assertEqual((None, 50), db.execute("SELECT body,deleted_at FROM messages WHERE logical_message_id='expired'").fetchone())
            self.assertEqual([], db.execute(query("due"), {"now": 200}).fetchall())
        finally:
            db.close()

    def test_deleted_reply_reference_still_requires_expiry_cleanup(self):
        db = create()
        try:
            message(db, "expired", 100, 50)
            message(db, "reply", None)
            db.execute("UPDATE messages SET reply_to_message_id='expired' WHERE logical_message_id='reply'")
            self.assertEqual(["expired"], [row[0] for row in db.execute(query("due"), {"now": 200})])
            db.execute(query("clearReplyReferences"), {"id": "expired"})
            self.assertEqual([], db.execute(query("due"), {"now": 200}).fetchall())
        finally:
            db.close()

    def test_file_creation_intent_survives_reopen_and_conversation_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.db"
            db = create(path)
            db.execute("INSERT INTO pending_media_files VALUES('/private/orphan','media',1)")
            db.commit(); db.close()
            db = sqlite3.connect(path)
            try:
                db.execute("DELETE FROM conversations")
                self.assertEqual([("/private/orphan", "media", 1)], db.execute(query("pendingMediaFiles")).fetchall())
                self.assertEqual((0,), db.execute(query("referencedFile"), {"path": "/private/orphan"}).fetchone())
            finally:
                db.close()


if __name__ == "__main__":
    unittest.main()
