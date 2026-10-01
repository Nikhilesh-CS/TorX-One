"""Execute actual 17->18->19->20 SQL and check preservation and recovery tables."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
base = root / "app/src/main/java/com/torxone/app"
schema = json.loads((root / "app/schemas/com.torxone.app.data.TorXDatabase/17.json").read_text(encoding="utf-8"))["database"]
db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys=ON")
for table in schema["entities"]:
    db.execute(table["createSql"].replace("${TABLE_NAME}", table["tableName"]))
    for index in table.get("indices", []):
        db.execute(index["createSql"].replace("${TABLE_NAME}", table["tableName"]))
db.execute("INSERT INTO conversations(conversationId,type,title,unread_count,is_pinned,is_archived,manually_unread,created_at) VALUES('chat','DIRECT','Alice',0,0,0,0,1)")
db.execute("INSERT INTO messages(logical_message_id,conversation_id,sender_id,type,body,direction,status,created_at,edit_version) VALUES('m','chat','peer','TEXT','keep me','INCOMING','DELIVERED',1,0)")
columns = {t["tableName"]: [r[1] for r in db.execute(f'PRAGMA table_info("{t["tableName"]}")')] for t in schema["entities"]}
def rows(table):
    names = ','.join(f'"{c}"' for c in columns[table])
    return db.execute(f'SELECT {names} FROM "{table}"').fetchall()
before = {table: rows(table) for table in columns}
for path, count in [(base / "privacy/SecurityPolicySchema.kt", 5), (base / "scheduling/SchedulingSchema.kt", 3),
                    (base / "privacy/MediaFileSchema.kt", 1)]:
    statements = re.findall(r'db\.execSQL\("([^"\n]+)"\)', path.read_text(encoding="utf-8"))
    assert len(statements) == count, f"Review extraction for {path.name}: {len(statements)}"
    for sql in statements:
        db.execute(sql)
for table in columns:
    assert rows(table) == before[table], f"Migration changed legacy data: {table}"
assert db.execute("SELECT expires_at FROM messages").fetchone() == (None,)
db.execute("INSERT INTO conversation_security_policy VALUES('chat',60000,1)")
db.execute("INSERT INTO privacy_file_cleanup VALUES('/private/retry',1)")
db.execute("INSERT INTO expired_media VALUES('media','relationship','chat',2)")
db.execute("INSERT INTO pending_media_files VALUES('/private/before-write','media',2)")
db.execute("INSERT INTO scheduled_messages VALUES('schedule','chat','me','later',NULL,100,1,'PENDING',0,0,NULL,1,1)")
assert db.execute("UPDATE scheduled_messages SET generation=2 WHERE scheduleId='schedule' AND generation=1").rowcount == 1
assert db.execute("UPDATE scheduled_messages SET state='SENDING' WHERE scheduleId='schedule' AND generation=1").rowcount == 0
db.execute("DELETE FROM conversations WHERE conversationId='chat'")
assert not db.execute("SELECT * FROM scheduled_messages").fetchall()
assert not db.execute("SELECT * FROM conversation_security_policy").fetchall()
assert db.execute("SELECT * FROM privacy_file_cleanup").fetchall(), "File cleanup intent must survive conversation removal"
assert db.execute("SELECT * FROM expired_media").fetchall(), "Late-frame tombstones must survive conversation removal"
assert db.execute("SELECT * FROM pending_media_files").fetchall(), "Creation intent must survive missing media/conversation"
assert not db.execute("PRAGMA foreign_key_check").fetchall()
print("PASS: actual 17->18->19->20 SQL preserves legacy columns, timer/schedule cascade, generation checks and durable file intent")
