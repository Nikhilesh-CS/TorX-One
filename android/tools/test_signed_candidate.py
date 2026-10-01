"""Synthetic signature and exact-candidate gate regressions; no production key use."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify_signed_candidate as gate

TRUSTED = "a" * 64
REPORT = ("Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n"
          "Number of signers: 1\nSigner #1 certificate DN: CN=TorX Release\n"
          f"Signer #1 certificate SHA-256 digest: {TRUSTED}\n")


class SignedCandidateTest(unittest.TestCase):
    def test_trusted_verified_signature(self):
        self.assertEqual(TRUSTED, gate.verify_signer(REPORT, ":".join(["aa"] * 32)))

    def test_mismatched_certificate_rejected(self):
        with self.assertRaises(ValueError):
            gate.verify_signer(REPORT, "b" * 64)

    def test_missing_trust_rejected(self):
        with self.assertRaises(ValueError):
            gate.verify_signer(REPORT, "")

    def test_debug_key_rejected_even_if_trusted(self):
        with self.assertRaises(ValueError):
            gate.verify_signer(REPORT.replace("CN=TorX Release", "C=US, O=Android, CN=Android Debug"), TRUSTED)

    def test_unsigned_or_weak_signature_rejected(self):
        for text in [REPORT.replace("Verifies\n", ""), REPORT.replace(": true", ": false")]:
            with self.assertRaises(ValueError):
                gate.verify_signer(text, TRUSTED)

    def test_multiple_signers_rejected(self):
        with self.assertRaises(ValueError):
            gate.verify_signer(REPORT.replace("Number of signers: 1", "Number of signers: 2"), TRUSTED)

    def test_approved_hash_matches_audit_and_beta(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "external-review.json").write_text(json.dumps({"candidate_apk_sha256": TRUSTED}))
            (root / "beta-devices.csv").write_text(f"candidate_apk_sha256\n{TRUSTED}\n")
            with patch.object(gate.ledger, "LEDGER", root):
                gate.verify_approved_hash(TRUSTED)
                with self.assertRaises(ValueError):
                    gate.verify_approved_hash("b" * 64)
                (root / "beta-devices.csv").write_text(f"candidate_apk_sha256\n{'b' * 64}\n")
                with self.assertRaises(ValueError):
                    gate.verify_approved_hash(TRUSTED)

    def test_actual_package_version_and_debuggable_flag(self):
        report = "package: name='com.torxone.app' versionCode='2' versionName='1.0.0'\n"
        self.assertEqual(("1.0.0", 2), gate.verify_package(report))
        for invalid in [report + "application-debuggable\n", report.replace("com.torxone.app", "other.package"), ""]:
            with self.assertRaises(ValueError):
                gate.verify_package(invalid)


if __name__ == "__main__":
    unittest.main()
