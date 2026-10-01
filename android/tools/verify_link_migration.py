"""Run actual 16->17 SQL against the exported schema and preserve legacy rows."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
schema = json.loads((root / "app/schemas/com.torxone.app.data.TorXDatabase/16.json").read_text(encoding="utf-8"))["database"]
sql = re.findall(r'db\.execSQL\("([^"\n]+)"\)', (root / "app/src/main/java/com/torxone/app/data/LinkSchema.kt").read_text(encoding="utf-8"))
assert len(sql) == 6, f"Revisit SQL extraction: expected 6 statements, found {len(sql)}"
db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys=ON")
for table in schema["entities"]:
    db.execute(table["createSql"].replace("${TABLE_NAME}", table["tableName"]))
    for index in table.get("indices", []):
        db.execute(index["createSql"].replace("${TABLE_NAME}", table["tableName"]))
db.execute("INSERT INTO conversations(conversationId,type,title,unread_count,is_pinned,is_archived,manually_unread,created_at) VALUES('chat','DIRECT','Alice',0,0,0,0,1)")
db.execute("INSERT INTO messages(logical_message_id,conversation_id,sender_id,type,body,direction,status,created_at,edit_version) VALUES('m','chat','peer','TEXT','https://example.com/old','INCOMING','DELIVERED',1,0)")
before = {table["tableName"]: db.execute(f'SELECT * FROM "{table["tableName"]}"').fetchall() for table in schema["entities"]}
for statement in sql:
    db.execute(statement)
for table, rows in before.items():
    assert db.execute(f'SELECT * FROM "{table}"').fetchall() == rows, f"Changed legacy table {table}"
assert db.execute("SELECT * FROM pending_link_index").fetchall() == [("m",)]
db.execute("INSERT INTO message_links VALUES('m','chat','https://example.com/old','example.com',1)")
db.execute("DELETE FROM pending_link_index")
db.execute("UPDATE messages SET body='https://example.org/new', edit_version=1 WHERE logical_message_id='m'")
assert not db.execute("SELECT * FROM message_links").fetchall()
assert db.execute("SELECT * FROM pending_link_index").fetchall() == [("m",)]
db.execute("INSERT INTO message_links VALUES('m','chat','https://example.org/new','example.org',1)")
db.execute("UPDATE messages SET body=NULL, deleted_at=2 WHERE logical_message_id='m'")
assert not db.execute("SELECT * FROM message_links").fetchall()
assert not db.execute("SELECT * FROM pending_link_index").fetchall()
db.execute("INSERT INTO messages(logical_message_id,conversation_id,sender_id,type,body,direction,status,created_at,edit_version) VALUES('next','chat','peer','TEXT','https://example.com/next','INCOMING','DELIVERED',2,0)")
assert db.execute("SELECT * FROM pending_link_index").fetchall() == [("next",)]
db.execute("DELETE FROM conversations WHERE conversationId='chat'")
assert not db.execute("SELECT * FROM pending_link_index").fetchall()
assert not db.execute("PRAGMA foreign_key_check").fetchall()
print("PASS: actual schema 16->17 preserves legacy data; link backfill, edit/deletion cleanup, queue and cascade")
