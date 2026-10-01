"""Synthetic Phase 21 approval records; fixtures are not a release approval."""
import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import validate_release_approval as gate


class ApprovalTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.sha = hashlib.sha256(b"synthetic").hexdigest()
        (self.root / "evidence.txt").write_bytes(b"synthetic")
        self.approval = dict(schema_version=1, status="APPROVED", candidate_source="synthetic",
                             candidate_apk_sha256=self.sha, certificate_sha256=self.sha,
                             version_name="1.0.0", version_code=2, reviewer="synthetic",
                             reviewed_at_utc="2026-10-01T00:00:00Z",
                             checks=[dict(id=name, status="PASS", evidence_path="evidence.txt", evidence_sha256=self.sha)
                                     for name in sorted(gate.CHECKS)])
        self.ledger_patch = patch.object(gate.ledger, "LEDGER", self.root)
        self.root_patch = patch.object(gate.ledger, "ROOT", self.root)
        self.ledger_patch.start(); self.root_patch.start()

    def tearDown(self):
        self.ledger_patch.stop(); self.root_patch.stop(); self.directory.cleanup()

    def check(self, **kwargs):
        (self.root / "release-approval.json").write_text(json.dumps(self.approval), encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            return gate.validate(complete=True, **kwargs)

    def test_complete_fixture_passes(self):
        self.assertEqual(0, self.check(apk_hash=self.sha, certificate=self.sha))

    def test_absent_human_approval_blocks(self):
        self.approval["status"] = "NOT_APPROVED"
        self.assertEqual(1, self.check())

    def test_different_apk_or_certificate_blocks(self):
        self.assertEqual(1, self.check(apk_hash="0" * 64))
        self.assertEqual(1, self.check(certificate="0" * 64))
        self.assertEqual(1, self.check(version_name="0.1.0"))
        self.assertEqual(1, self.check(version_code=1))

    def test_mutated_evidence_blocks(self):
        (self.root / "evidence.txt").write_text("changed")
        self.assertEqual(1, self.check())

    def test_unpassed_check_blocks(self):
        for status in ["NOT_RUN", "FAIL", "BLOCKED"]:
            self.approval["checks"][0]["status"] = status
            self.assertEqual(1, self.check())

    def test_removed_or_duplicate_gate_rejected(self):
        check = self.approval["checks"].pop()
        with self.assertRaises(ValueError):
            self.check()
        self.approval["checks"].extend([check, check])
        with self.assertRaises(ValueError):
            self.check()

    def test_missing_reviewer_blocks(self):
        self.approval["reviewer"] = ""
        self.assertEqual(1, self.check())

    def test_unzoned_date_rejected(self):
        self.approval["reviewed_at_utc"] = "2026-10-01T00:00:00"
        with self.assertRaises(ValueError):
            self.check()


if __name__ == "__main__":
    unittest.main()
