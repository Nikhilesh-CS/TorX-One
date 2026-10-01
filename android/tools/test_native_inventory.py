"""Native inventory regressions with synthetic ZIPs, not executable libraries."""
import hashlib
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

import inventory_apk_native as tool


class NativeInventoryTest(unittest.TestCase):
    def check_archive(self, entries):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    for name, data in entries:
                        archive.writestr(name, data)
            return tool.inventory(apk)

    def test_actual_entry_hashes_and_abis(self):
        report = self.check_archive([("lib/arm64-v8a/libfixture.so", b"one"), ("lib/x86_64/libfixture.so", b"two")])
        self.assertEqual("INVENTORIED", report["status"])
        self.assertEqual(2, len(report["libraries"]))
        self.assertEqual(hashlib.sha256(b"one").hexdigest(), report["libraries"][0]["sha256"])

    def test_empty_inventory_fails(self):
        with self.assertRaises(ValueError):
            self.check_archive([("README", b"not native")])

    def test_duplicate_or_unexpected_path_fails(self):
        for entries in [[("lib/arm64-v8a/libfixture.so", b"one")] * 2,
                        [("lib/arm64-v8a/../libfixture.so", b"one")], [("assets/libfixture.so", b"one")]]:
            with self.assertRaises(ValueError):
                self.check_archive(entries)

    def test_inventory_size_is_bounded(self):
        with patch.object(tool, "MAX_TOTAL_BYTES", 2):
            with self.assertRaises(ValueError):
                self.check_archive([("lib/arm64-v8a/libfixture.so", b"three")])


if __name__ == "__main__":
    unittest.main()
