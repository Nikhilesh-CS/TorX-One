"""Synthetic ledger regression tests. Fixtures are not actual review/beta proof."""
import contextlib
import csv
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
import validate_release_evidence as gate


class EvidenceGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.previous = gate.ROOT, gate.LEDGER, gate.SCENARIOS, gate.SINGLE_DEVICE
        gate.ROOT = Path(self.temp.name)
        gate.LEDGER = gate.ROOT / "docs/validation"
        gate.SCENARIOS, gate.SINGLE_DEVICE = {"fixture"}, set()
        gate.LEDGER.mkdir(parents=True)
        self.sha = hashlib.sha256(b"synthetic fixture only").hexdigest()
        (gate.ROOT / "evidence.txt").write_bytes(b"synthetic fixture only")
        cohorts = sorted(gate.COHORTS)
        self.devices = []
        for index, cohort in enumerate(cohorts):
            self.devices.append(dict(cohort=cohort, device_id=f"fixture-{index}", manufacturer="fixture",
                                     model="fixture", api_level="26" if cohort == "oldest_supported" else "36",
                                     os_build="fixture", ram_mb="2048", candidate_apk_sha256=self.sha))
        self.csv("beta-devices.csv", list(self.devices[0]), self.devices)
        self.csv("beta-scenarios.csv", ["id", "scenario", "expected", "paired"],
                 [dict(id="fixture", scenario="synthetic", expected="fixture", paired="true")])
        self.results = [dict(cohort=row["cohort"], scenario_id="fixture", status="PASS",
                             paired_device_id=self.devices[(index + 1) % len(self.devices)]["device_id"],
                             candidate_apk_sha256=self.sha, network_conditions="synthetic", observed_at_utc="2026-10-01T00:00:00Z",
                             observer="fixture", evidence_path="evidence.txt", evidence_sha256=self.sha, notes="fixture")
                        for index, row in enumerate(self.devices)]
        self.csv("beta-results.csv", gate.RESULT_FIELDS, self.results)
        self.review = dict(schema_version=1, status="COMPLETE", reviewer="Synthetic fixture reviewer",
                           candidate_source="fixture", candidate_apk_sha256=self.sha, penetration_test_complete=True,
                           covered_surfaces=sorted(gate.SURFACES), report_path="evidence.txt", report_sha256=self.sha,
                           independent_signoff=True)
        self.save_review()
        self.fields = ["id", "severity", "surface", "status", "fix_source", "regression_evidence", "independent_retest"]
        for name in ["audit-findings.csv", "beta-findings.csv"]:
            self.csv(name, self.fields, [])

    def tearDown(self):
        gate.ROOT, gate.LEDGER, gate.SCENARIOS, gate.SINGLE_DEVICE = self.previous
        self.temp.cleanup()

    def csv(self, name, fields, rows):
        with (gate.LEDGER / name).open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)

    def save_review(self):
        (gate.LEDGER / "external-review.json").write_text(json.dumps(self.review), encoding="utf-8")

    def check(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return gate.validate(complete=True)

    def test_complete_synthetic_fixture(self):
        self.assertEqual(0, self.check())

    def test_no_independent_review_blocks(self):
        self.review["independent_signoff"] = False
        self.save_review()
        self.assertEqual(1, self.check())

    def test_not_run_and_candidate_mismatch_block(self):
        for changes in [dict(status="NOT_RUN"), dict(candidate_apk_sha256="0" * 64)]:
            with self.subTest(changes=changes):
                rows = [dict(row) for row in self.results]
                rows[0].update(changes)
                self.csv("beta-results.csv", gate.RESULT_FIELDS, rows)
                self.assertEqual(1, self.check())

    def test_duplicate_matrix_rejected(self):
        self.csv("beta-results.csv", gate.RESULT_FIELDS, self.results + [self.results[0]])
        self.assertRaises(ValueError, self.check)

    def test_outside_workspace_and_modified_evidence_block(self):
        self.assertFalse(gate.evidence_valid("../outside.txt", self.sha))
        (gate.ROOT / "evidence.txt").write_text("changed", encoding="utf-8")
        self.assertEqual(1, self.check())

    def test_open_high_finding_and_missing_independent_retest_block(self):
        for status, retested in [("OPEN", "false"), ("RETESTED", "false")]:
            with self.subTest(status=status):
                row = dict(id="fixture-high", severity="HIGH", surface="media", status=status,
                           fix_source="fixture-fix", regression_evidence="fixture-test", independent_retest=retested)
                self.csv("audit-findings.csv", self.fields, [row])
                self.assertEqual(1, self.check())

    def test_initialization_does_not_replace_existing_results(self):
        before = (gate.LEDGER / "beta-results.csv").read_bytes()
        with contextlib.redirect_stdout(io.StringIO()):
            gate.validate(initialize=True)
        self.assertEqual(before, (gate.LEDGER / "beta-results.csv").read_bytes())

    def test_removing_required_scenario_or_pairing_rejected(self):
        self.csv("beta-scenarios.csv", ["id", "scenario", "expected", "paired"], [])
        self.assertRaises(ValueError, self.check)
        self.csv("beta-scenarios.csv", ["id", "scenario", "expected", "paired"],
                 [dict(id="fixture", scenario="synthetic", expected="fixture", paired="false")])
        self.assertRaises(ValueError, self.check)


if __name__ == "__main__":
    unittest.main()
