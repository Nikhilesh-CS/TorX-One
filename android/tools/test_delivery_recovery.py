import json
import re
import sqlite3
import unittest
import tempfile
from pathlib import Path
from analyze_delivery_trace import parse, classify

ANDROID = Path(__file__).resolve().parents[1]


class DeliveryRecoveryTest(unittest.TestCase):
    def test_late_chunk_cannot_resurrect_paused_cancelled_or_completed_transfer(self):
        schema = json.loads((ANDROID / "app/schemas/com.torxone.app.data.TorXDatabase/20.json").read_text(encoding="utf-8-sig"))
        transfer = next(e for e in schema["database"]["entities"] if e["tableName"] == "media_transfers")
        source = (ANDROID / "app/src/main/java/com/torxone/app/data/dao/Daos.kt").read_text(encoding="utf-8-sig")
        sql = re.search(r'@Query\("([^"\n]+)"\)\s+suspend fun recordChunkIfMissing', source).group(1)
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.execute(transfer["createSql"].replace("${TABLE_NAME}", "media_transfers"))
        db.execute("INSERT INTO media_transfers(transfer_id,media_id,conversation_id,relationship_id,direction,status,total_chunks,completed_chunks,chunk_bitmask,total_bytes,bytes_transferred,chunk_size,temp_encrypted_path,updated_at) VALUES('transfer','media','chat','rel','DOWNLOAD','ACTIVE',2,0,'',8,0,4,'/private/file',1)")
        args = dict(transferId="transfer", chunkIndex=0, chunkBytes=4, status="ACTIVE", updatedAt=20)
        for status in ["PAUSED", "CANCELLED", "COMPLETED", "FAILED", "IDLE"]:
            db.execute("UPDATE media_transfers SET status=? WHERE transfer_id='transfer'", (status,))
            self.assertEqual(0, db.execute(sql, args).rowcount)
            self.assertEqual((status, 0, "", 0, 1), db.execute("SELECT status,completed_chunks,chunk_bitmask,bytes_transferred,updated_at FROM media_transfers").fetchone())
        db.execute("UPDATE media_transfers SET status='QUEUED'")
        self.assertEqual(1, db.execute(sql, args).rowcount)
        self.assertEqual(0, db.execute(sql, args).rowcount)
        self.assertEqual(("ACTIVE", 1, "0", 4), db.execute("SELECT status,completed_chunks,chunk_bitmask,bytes_transferred FROM media_transfers").fetchone())

    def test_late_and_duplicate_group_ack_preserve_read_and_first_delivery_time(self):
        schema = json.loads((ANDROID / "app/schemas/com.torxone.app.data.TorXDatabase/20.json").read_text(encoding="utf-8-sig"))
        entity = next(e for e in schema["database"]["entities"] if e["tableName"] == "group_message_deliveries")
        source = (ANDROID / "app/src/main/java/com/torxone/app/data/dao/GroupDaos.kt").read_text(encoding="utf-8-sig")
        sql = re.search(r'@Query\("""((?:(?!""").)*)"""\)\s+suspend fun markDelivered', source, re.DOTALL).group(1)
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.execute(entity["createSql"].replace("${TABLE_NAME}", "group_message_deliveries"))
        db.execute("INSERT INTO group_message_deliveries VALUES('delivery','message','peer','rel',NULL,'READ',20,30,1,40)")
        # Older ACK cannot replace READ or move its timestamps backwards.
        args = dict(logicalMessageId="message", recipientIdentityId="peer", deliveredAt=10, updatedAt=10)
        db.execute(sql, args)
        self.assertEqual(("READ", 20, 30, 40), db.execute("SELECT status,delivered_at,read_at,updated_at FROM group_message_deliveries").fetchone())
        args.update(deliveredAt=80, updatedAt=80)
        db.execute(sql, args)
        self.assertEqual(("READ", 20, 30, 80), db.execute("SELECT status,delivered_at,read_at,updated_at FROM group_message_deliveries").fetchone())

    def test_relationship_only_failures_do_not_contaminate_another_delivery(self):
        events = parse([
            'TORX_DIAG event=transport_accepted relationship=abcdefabcdef delivery=123456123456',
            'TORX_DIAG event=route_lookup relationship=abcdefabcdef delivery=none present=false',
            'TORX_DIAG event=tor_connect_timeout relationship=abcdefabcdef delivery=none',
        ])
        self.assertEqual('REMOTE_CAPTURE_REQUIRED', classify(events)[0]['classification'])
        events += parse(['TORX_DIAG event=tor_connect_timeout relationship=abcdefabcdef delivery=123456123456'])
        self.assertEqual('B_TOR_CONNECT_TIMEOUT', classify(events)[0]['classification'])

    def test_waiting_head_and_route_survive_schema20_reopen(self):
        schema = json.loads((ANDROID / "app/schemas/com.torxone.app.data.TorXDatabase/20.json").read_text(encoding="utf-8-sig"))
        entities = {e["tableName"]: e for e in schema["database"]["entities"]}
        source = (ANDROID / "app/src/main/java/com/torxone/app/data/dao/Daos.kt").read_text(encoding="utf-8-sig")
        query = re.search(r'@Query\("(SELECT \* FROM outbox[^\n]+)"\)\s+suspend fun getPending', source).group(1)
        with tempfile.TemporaryDirectory() as folder:
            database = Path(folder) / "fixture.db"
            db = sqlite3.connect(database)
            self.addCleanup(db.close)
            db.execute("PRAGMA foreign_keys=ON")
            for name in ["pair_relationships", "peer_tor_endpoints", "outbox"]:
                db.execute(entities[name]["createSql"].replace("${TABLE_NAME}", name))
            db.execute("INSERT INTO pair_relationships(relationship_id,local_identity_id,contact_id,root_secret,state,generation,created_at,crypto_format_version) VALUES('rel','local','contact',?,'ACTIVE',1,1,3)", (bytes(32),))
            db.execute("INSERT INTO peer_tor_endpoints(relationship_id,onion_address,port,updated_at,source) VALUES('rel',?,17654,1,'SIGNED_PAIRING')", ("a"*56+".onion",))
            sql = "INSERT INTO outbox(deliveryId,logical_message_id,conversation_id,connection_id,queue_address,ciphertext,queue_authenticator,status,priority,attempt_count,next_attempt_at,created_at,updated_at,expects_ack,application_sequence,relationship_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            db.execute(sql, ('head','first','chat','conn','q',b'cipher',bytes(32),'WAITING_FOR_PEER',10,8,0,1,1,1,1,'rel'))
            db.execute(sql, ('next','second','chat','conn','rotated',b'nextcipher',bytes(32),'QUEUED',10,0,0,2,2,1,2,'rel'))
            db.commit()
            db.close()
            db = sqlite3.connect(database)
            self.addCleanup(db.close)
            self.assertEqual(['head','next'], [r[0] for r in db.execute(query)])
            self.assertEqual(b'cipher', db.execute("SELECT ciphertext FROM outbox WHERE deliveryId='head'").fetchone()[0])
            exact_query = re.search(r'@Query\("(SELECT \* FROM outbox[^\n]+)"\)\s+suspend fun getByDeliveryId', source).group(1)
            for terminal in ['FAILED', 'EXPIRED']:
                db.execute("UPDATE outbox SET status=? WHERE deliveryId='head'", (terminal,))
                self.assertEqual(['head','next'], [r[0] for r in db.execute(query)])
                self.assertEqual(b'cipher', db.execute(exact_query, {'deliveryId': 'head'}).fetchone()[5])
            db.execute(sql, ('control','terminal-control','chat','conn','q',b'controlcipher',bytes(32),'FAILED',10,8,0,3,3,1,None,'rel'))
            self.assertEqual(['head','next'], [r[0] for r in db.execute(query)])
            self.assertIsNone(db.execute(exact_query, {'deliveryId': 'control'}).fetchone())
            db.execute("DELETE FROM outbox WHERE deliveryId='head'")
            self.assertEqual(['next'], [r[0] for r in db.execute(query)])
            self.assertEqual('SIGNED_PAIRING', db.execute("SELECT source FROM peer_tor_endpoints").fetchone()[0])
            db.close()

    def test_ack_and_receiver_rejection_classification(self):
        base = "TORX_DIAG event={} relationship=abcdefabcdef delivery=123456123456"
        events = parse([base.format('transport_accepted'), base.format('queue_auth_failure')])
        self.assertEqual('D_RECEIVER_REJECTED', classify(events)[0]['classification'])
        events += parse([base.format('ack_received')])
        self.assertEqual('D_RECEIVER_REJECTED', classify(events)[0]['classification'])
        self.assertEqual('VERIFIED_ACK_COMMIT_UNCONFIRMED', classify(parse([base.format('ack_received')]))[0]['classification'])
        events += parse([base.format('message_delivered')])
        self.assertEqual('AUTHENTICATED_ACK_CONFIRMED', classify(events)[0]['classification'])

    def test_missing_remote_log_does_not_prove_loss(self):
        events = parse(['TORX_DIAG event=transport_accepted relationship=abcdefabcdef delivery=123456123456'])
        self.assertEqual('REMOTE_CAPTURE_REQUIRED', classify(events)[0]['classification'])
        self.assertEqual('C_NO_RECEIVER_FRAME_IN_PAIRED_WINDOW', classify(events, True)[0]['classification'])

    def test_raw_secrets_or_legacy_logs_are_not_echoed(self):
        events = parse(['TORX_DIAG legacy onion=private.onion plaintext=secret', 'not diagnostics event=ack_received delivery=123456123456'])
        self.assertEqual([], classify(events))


if __name__ == '__main__':
    unittest.main()
