"""Regression-check the actual Room contact queries against SQLite in every row order."""
import itertools
import re
import sys
from pathlib import Path

sys.dont_write_bytecode = True
from verify_peer_endpoint_migration import ROOT, create_schema, seed


def main():
    source = (ROOT / "android/app/src/main/java/com/torxone/app/data/dao/Daos.kt").read_text(encoding="utf-8")
    queries = []
    for method, parameter, value in [("getByConversationId", "conversationId", "chat"),
                                      ("getByRemoteIdentityId", "remoteIdentityId", "peer")]:
        match = re.search(r'@Query\("([^"\n]*)"\)\s*suspend fun ' + method + r'\(', source)
        assert match, f"No query found for {method}"
        queries.append((match.group(1), {parameter: value}))
    fixtures = [("broken", 999, False, False, 1), ("pending", 100, True, False, 1),
                ("old", 99, True, True, 1), ("rotated", 1, True, True, 2)]
    for order in itertools.permutations(fixtures):
        db, schema = create_schema(14)
        for identifier, timestamp, usable, active, generation in order:
            relation = "rel-" + identifier
            seed(db, schema, "contacts", {"contactId": identifier, "relationship_id": relation,
                 "conversation_id": "chat", "remote_identity_id": "peer", "created_at": timestamp})
            if usable:
                seed(db, schema, "pair_relationships", {"relationship_id": relation, "contact_id": identifier,
                     "state": "ACTIVE" if active else "LOCAL_ESTABLISHED"})
                seed(db, schema, "connections", {"connection_id": "conn-" + identifier,
                     "relationship_id": relation, "generation": generation,
                     "state": "ACTIVE" if active else "LOCAL_ESTABLISHED"})
                primary = next(e for e in schema["entities"] if e["tableName"] == "sessions")["primaryKey"]["columnNames"]
                values = {column: "session-" + identifier for column in primary}
                values["relationship_id"] = relation
                seed(db, schema, "sessions", values)
        for sql, arguments in queries:
            assert db.execute(sql, arguments).fetchone()[0] == "rotated", "Selection depends on row order or ignores secure state"
        db.execute("DELETE FROM contacts WHERE contactId IN ('old','rotated')")
        for sql, arguments in queries:
            assert db.execute(sql, arguments).fetchone()[0] == "pending", "Broken lane outranked an existing session"
        db.close()
    print("PASS: actual contact queries prefer healthy/active/newest generation in all 24 insertion orders")


if __name__ == "__main__":
    main()
