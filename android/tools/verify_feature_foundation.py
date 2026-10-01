"""Exercise the actual schema 15->16 SQL and database-owned FTS maintenance."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
schema = json.loads((root / "app/schemas/com.torxone.app.data.TorXDatabase/15.json").read_text(encoding="utf-8"))["database"]
source = (root / "app/src/main/java/com/torxone/app/data/FeatureSchema.kt").read_text(encoding="utf-8")
sql = re.findall(r'db\.execSQL\("([^"\n]+)"\)', source)
assert len(sql) == 14, f"Revisit SQL extraction: expected 14 statements, found {len(sql)}"
db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys=ON")
for table in schema["entities"]:
    db.execute(table["createSql"].replace("${TABLE_NAME}", table["tableName"]))
    for index in table.get("indices", []):
        db.execute(index["createSql"].replace("${TABLE_NAME}", table["tableName"]))
db.execute("INSERT INTO conversations(conversationId,type,title,unread_count,is_pinned,is_archived,manually_unread,created_at) VALUES('chat','DIRECT','Alice',0,0,0,0,1)")
db.execute("INSERT INTO messages(logical_message_id,conversation_id,sender_id,type,body,direction,status,created_at,edit_version) VALUES('m','chat','peer','TEXT','original searchable','INCOMING','DELIVERED',1,0)")
before = {table["tableName"]: db.execute(f'SELECT * FROM "{table["tableName"]}"').fetchall() for table in schema["entities"]}
for statement in sql:
    db.execute(statement)
for table, rows in before.items():
    assert db.execute(f'SELECT * FROM "{table}"').fetchall() == rows, f"Changed legacy table {table}"
assert db.execute("SELECT messageId FROM message_search WHERE message_search MATCH 'original'").fetchall() == [("m",)]
assert db.execute('SELECT messageId FROM message_search WHERE message_search MATCH ?', ('"orig*" AND "search*"',)).fetchall() == [("m",)]
db.execute("UPDATE messages SET body='changed searchable' WHERE logical_message_id='m'")
assert not db.execute("SELECT * FROM message_search WHERE message_search MATCH 'original'").fetchall()
assert db.execute("SELECT messageId FROM message_search WHERE message_search MATCH 'changed'").fetchall() == [("m",)]
db.execute("INSERT INTO media(media_id,message_id,conversation_id,media_type,mime_type,file_name,file_size,encrypted_sha256,media_key,status,transfer_progress,created_at) VALUES('media','m','chat','DOCUMENT','application/pdf','report.pdf',100,?,?,'COMPLETE',1,1)", ("0" * 64, bytes(32)))
assert db.execute("SELECT messageId FROM message_search WHERE message_search MATCH 'report'").fetchall() == [("m",)]
db.execute("UPDATE media SET file_name='invoice.pdf' WHERE media_id='media'")
assert not db.execute("SELECT * FROM message_search WHERE message_search MATCH 'report'").fetchall()
assert db.execute("SELECT messageId FROM message_search WHERE message_search MATCH 'invoice'").fetchall() == [("m",)]
db.execute("INSERT INTO conversation_drafts VALUES('chat','draft text','m',1)")
db.execute("INSERT INTO starred_messages VALUES('m','chat',1)")
db.execute("UPDATE messages SET body=NULL, deleted_at=2 WHERE logical_message_id='m'")
assert not db.execute("SELECT * FROM message_search").fetchall()
assert db.execute("SELECT logical_message_id FROM messages").fetchall() == [("m",)]
db.execute("DELETE FROM conversations WHERE conversationId='chat'")
assert not db.execute("SELECT * FROM conversation_drafts").fetchall()
assert not db.execute("SELECT * FROM starred_messages").fetchall()
assert not db.execute("PRAGMA foreign_key_check").fetchall()
print("PASS: actual schema 15->16 preserves legacy data; FTS backfill, edits, filenames, deletion and local cascades")

for path in ["chat/ChatService.kt", "chat/ChatProductivityService.kt", "groups/GroupService.kt", "profile/SettingsViewModel.kt"]:
    text = (root / "app/src/main/java/com/torxone/app" / path).read_text(encoding="utf-8")
    assert not re.search(r"^import com\.torxone\.app\.(transport|calls)\.", text, re.M), f"Feature directly imports transport: {path}"
print("PASS: feature services retain Room/ratchet/outbox boundaries")
