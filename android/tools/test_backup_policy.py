"""Check explicit legacy/cloud/device-transfer exclusions in source XML."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

MAIN = Path(__file__).resolve().parents[1] / "app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"
DOMAINS = {"root", "file", "database", "sharedpref", "external", "device_root",
           "device_file", "device_database", "device_sharedpref"}


class BackupPolicyTest(unittest.TestCase):
    def assert_exclusions(self, section):
        self.assertIsNotNone(section)
        self.assertEqual([], section.findall("include"))
        rules = section.findall("exclude")
        self.assertEqual(DOMAINS, {rule.get("domain") for rule in rules})
        self.assertEqual(len(DOMAINS), len(rules))
        self.assertTrue(all(rule.get("path") == "." for rule in rules))

    def test_manifest_retains_backup_flag_and_wires_both_rule_formats(self):
        app = ET.parse(MAIN / "AndroidManifest.xml").getroot().find("application")
        self.assertEqual("false", app.get(ANDROID + "allowBackup"))
        self.assertEqual("@xml/backup_rules", app.get(ANDROID + "fullBackupContent"))
        self.assertEqual("@xml/data_extraction_rules", app.get(ANDROID + "dataExtractionRules"))

    def test_each_backup_and_transfer_domain_explicitly_excluded(self):
        legacy = ET.parse(MAIN / "res/xml/backup_rules.xml").getroot()
        self.assertEqual("full-backup-content", legacy.tag)
        self.assert_exclusions(legacy)
        modern = ET.parse(MAIN / "res/xml/data_extraction_rules.xml").getroot()
        self.assertEqual("data-extraction-rules", modern.tag)
        self.assert_exclusions(modern.find("cloud-backup"))
        self.assert_exclusions(modern.find("device-transfer"))


if __name__ == "__main__":
    unittest.main()
