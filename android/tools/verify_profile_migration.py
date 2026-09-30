"""Run the actual 14->15 migration and compare its result with the exported Room schema."""
import sys
sys.dont_write_bytecode = True
import re
from verify_peer_endpoint_migration import ROOT, create_schema, seed


def main():
    db, old = create_schema(14)
    target, new = create_schema(15)
    seed(db, old, "pair_relationships", {"relationship_id": "rel", "contact_id": "contact"})
    seed(db, old, "contacts", {"contactId": "contact", "relationship_id": "rel"})
    seed(db, old, "connections", {"connection_id": "conn", "relationship_id": "rel", "send_sequence": 41, "recv_sequence": 37})
    seed(db, old, "sessions", {"relationship_id": "rel"})
    seed(db, old, "outbox", {"deliveryId": "delivery", "connection_id": "conn"})
    seed(db, old, "peer_tor_endpoints", {"relationship_id": "rel"})
    preserved = {entity["tableName"]: db.execute(f'SELECT * FROM "{entity["tableName"]}"').fetchall() for entity in old["entities"]}
    source = (ROOT / "android/app/src/main/java/com/torxone/app/data/TorXDatabase.kt").read_text(encoding="utf-8")
    section = source.split("val MIGRATION_14_15 =", 1)[1].split("fun getInstance(", 1)[0]
    statements = re.findall(r'db\.execSQL\("([^"\n]*)"\)', section)
    assert len(statements) == 2
    for sql in statements:
        db.execute(sql)
    for entity in old["entities"]:
        table = entity["tableName"]
        columns = ','.join(f'"{field["columnName"]}"' for field in entity["fields"])
        assert preserved[table] == db.execute(f'SELECT {columns} FROM "{table}"').fetchall(), f"Changed existing data: {table}"
    for entity in new["entities"]:
        table = entity["tableName"]
        for pragma in ("table_info", "foreign_key_list"):
            assert db.execute(f'PRAGMA {pragma}("{table}")').fetchall() == target.execute(f'PRAGMA {pragma}("{table}")').fetchall(), table
    assert db.execute("SELECT about, profile_updated_at FROM contacts").fetchone() == ("", 0)
    assert not db.execute("PRAGMA foreign_key_check").fetchall()
    print("PASS: schema 14->15, profile defaults and all existing tables/keys/messages/queues preserved")


if __name__ == "__main__":
    main()
