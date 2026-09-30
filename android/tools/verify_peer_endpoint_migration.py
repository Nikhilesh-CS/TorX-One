"""Exercise the actual 13->14 migration SQL against exported Room schemas using SQLite."""
import json
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "android/app/schemas/com.torxone.app.data.TorXDatabase"


def create_schema(version):
    schema = json.loads((SCHEMAS / f"{version}.json").read_text(encoding="utf-8"))["database"]
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    for entity in schema["entities"]:
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    return db, schema


def seed(db, schema, table, overrides):
    entity = next(e for e in schema["entities"] if e["tableName"] == table)
    values = {}
    for field in entity["fields"]:
        column = field["columnName"]
        if column in overrides:
            values[column] = overrides[column]
        elif field.get("notNull", False):
            values[column] = {"TEXT": "fixture", "INTEGER": 0, "BLOB": b"protected-secret"}[field["affinity"]]
        else:
            values[column] = None
    columns = ",".join(f'"{c}"' for c in values)
    db.execute(f'INSERT INTO "{table}" ({columns}) VALUES ({",".join("?" for _ in values)})', list(values.values()))


def main():
    db, old = create_schema(13)
    target, new = create_schema(14)
    seed(db, old, "pair_relationships", {"relationship_id": "rel", "contact_id": "contact"})
    seed(db, old, "contacts", {"contactId": "contact", "relationship_id": "rel"})
    seed(db, old, "connections", {"connection_id": "conn", "relationship_id": "rel", "send_sequence": 41, "recv_sequence": 37})
    seed(db, old, "sessions", {"relationship_id": "rel"})
    seed(db, old, "outbox", {"deliveryId": "bootstrap-delivery", "logical_message_id": "wrong-random-id", "connection_id": "conn", "queue_address": "invite-123", "expects_ack": 0})
    seed(db, old, "outbox", {"deliveryId": "message-delivery", "logical_message_id": "message", "connection_id": "conn", "queue_address": "send", "expects_ack": 1})
    preserved = {table: db.execute(f'SELECT * FROM "{table}"').fetchall() for table in ["contacts", "pair_relationships", "connections", "sessions"]}
    ordinary = db.execute("SELECT * FROM outbox WHERE deliveryId='message-delivery'").fetchone()
    source = (ROOT / "android/app/src/main/java/com/torxone/app/data/TorXDatabase.kt").read_text(encoding="utf-8")
    section = source.split("val MIGRATION_13_14 =", 1)[1].split("fun getInstance(", 1)[0]
    statements = re.findall(r'db\.execSQL\("""(.*?)"""\.trimIndent\(\)\)|db\.execSQL\("([^"\n]*)"\)', section, re.S)
    assert len(statements) == 2, "Migration changed: update this verifier to exercise every statement"
    for multiline, inline in statements:
        db.execute(multiline or inline)
    for table, rows in preserved.items():
        assert rows == db.execute(f'SELECT * FROM "{table}"').fetchall(), f"Lost data in {table}"
    assert ordinary == db.execute("SELECT * FROM outbox WHERE deliveryId='message-delivery'").fetchone()
    assert db.execute("SELECT deliveryId, logical_message_id, expects_ack, relationship_id FROM outbox WHERE queue_address='invite-123'").fetchone() == ("bootstrap-delivery", "123", 1, "rel")
    for entity in new["entities"]:
        table = entity["tableName"]
        for pragma in ["table_info", "foreign_key_list"]:
            assert db.execute(f'PRAGMA {pragma}("{table}")').fetchall() == target.execute(f'PRAGMA {pragma}("{table}")').fetchall(), f"Room schema mismatch: {table} {pragma}"
    db.execute("INSERT INTO peer_tor_endpoints VALUES (?,?,?,?,?)", ("rel", "a" * 56 + ".onion", 17654, 1, "SIGNED_PAIRING"))
    db.execute("UPDATE pair_relationships SET state='ACTIVE' WHERE relationship_id='rel'")
    assert db.execute("SELECT COUNT(*) FROM peer_tor_endpoints").fetchone()[0] == 1
    db.execute("DELETE FROM pair_relationships WHERE relationship_id='rel'")
    assert db.execute("SELECT COUNT(*) FROM peer_tor_endpoints").fetchone()[0] == 0
    assert not db.execute("PRAGMA foreign_key_check").fetchall()
    print("PASS: schema 13->14, preserved contacts/keys/sequences/outbox, bootstrap repair, endpoint foreign key")


if __name__ == "__main__":
    main()
