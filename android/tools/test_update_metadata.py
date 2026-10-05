import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from generate_update_metadata import generate


class UpdateMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "signed.apk"
        self.apk.write_bytes(b"fixture signed APK bytes")
        self.gradle = self.root / "build.gradle.kts"
        self.gradle.write_text('versionCode = 3\nversionName = "0.1.0"\nminSdk = 26\n')
        self.cert = "a" * 64
        self.signature = f"Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\nNumber of signers: 1\nSigner #1 certificate DN: CN=TorX One\nSigner #1 certificate SHA-256 digest: {self.cert}\n"
        self.package = "package: name='com.torxone.app' versionCode='3' versionName='0.1.0'\n"

    def run_generation(self, **overrides):
        values = dict(apk=self.apk, output=self.root / "out", gradle=self.gradle,
                      tag="v0.1.0-build3", package_report=self.package,
                      signature_report=self.signature, trusted=self.cert, notes="Fixes")
        values.update(overrides)
        return generate(**values)

    def test_asset_name_hash_and_versions_come_from_actual_input(self):
        metadata = self.run_generation()
        self.assertEqual(metadata["versionCode"], 3)
        self.assertEqual(metadata["apkAsset"], "torxone-v0.1.0-build3.apk")
        self.assertEqual(metadata["sha256"], hashlib.sha256(self.apk.read_bytes()).hexdigest())
        self.assertEqual(json.loads((self.root / "out/update.json").read_text()), metadata)
        self.assertIn("update.json", (self.root / "out/SHA256SUMS").read_text())

    def test_mismatched_apk_version_is_rejected(self):
        with self.assertRaises(ValueError):
            self.run_generation(package_report=self.package.replace("versionCode='3'", "versionCode='2'"))

    def test_wrong_package_is_rejected(self):
        with self.assertRaises(ValueError):
            self.run_generation(package_report=self.package.replace("com.torxone.app", "evil.app"))

    def test_tag_mismatch_is_rejected(self):
        with self.assertRaises(ValueError):
            self.run_generation(tag="v0.1.0-build2")

    def test_wrong_signer_is_rejected(self):
        with self.assertRaises(ValueError):
            self.run_generation(trusted="b" * 64)

    def test_unsigned_apk_is_rejected(self):
        with self.assertRaises(ValueError):
            self.run_generation(signature_report="DOES NOT VERIFY")

    def test_minimum_and_release_notes_are_bounded(self):
        with self.assertRaises(ValueError):
            self.run_generation(minimum_supported=4)
        with self.assertRaises(ValueError):
            self.run_generation(notes="x" * 4001)

    def test_modified_apk_changes_generated_hash(self):
        before = self.run_generation()["sha256"]
        self.apk.write_bytes(b"different fixture bytes")
        self.assertNotEqual(before, self.run_generation()["sha256"])


if __name__ == "__main__":
    unittest.main()
