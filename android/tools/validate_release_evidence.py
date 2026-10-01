"""Validate the phase 19/20 evidence ledger; never manufacture test results."""
import argparse
import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LEDGER = ROOT / "docs/validation"
COHORTS = {"samsung", "pixel", "oneplus", "xiaomi", "motorola", "nothing", "low_end", "oldest_supported", "newest_available"}
SURFACES = {"cryptography", "android", "storage", "tor", "nearby", "state_machine", "groups", "media", "calls", "parsers", "privacy"}
SCENARIOS = {"BT_TOGGLE", "WIFI_TOGGLE", "INTERNET_LOSS", "TOR_LOSS", "NETWORK_SWITCH", "AIRPLANE",
             "PROCESS_KILL", "REBOOT", "BATTERY_SAVER", "LOW_STORAGE", "LOW_MEMORY", "HUGE_CHAT",
             "GROUPS", "VOICE_CALL", "VIDEO_CALL", "LARGE_FILE", "LONG_OFFLINE", "NEARBY_MOVE", "UPGRADE", "EXPIRY"}
SINGLE_DEVICE = {"LOW_STORAGE", "HUGE_CHAT"}
RESULT_FIELDS = ["cohort", "scenario_id", "status", "paired_device_id", "candidate_apk_sha256",
                 "network_conditions", "observed_at_utc", "observer", "evidence_path", "evidence_sha256", "notes"]


def read_csv(path, required):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not set(required).issubset(reader.fieldnames or []):
            raise ValueError(f"Missing required columns: {path.name}")
        rows = list(reader)
        if any(None in row or any(value is None for value in row.values()) for row in rows):
            raise ValueError(f"Malformed row: {path.name}")
        return rows


def hash_valid(value):
    return len(value) == 64 and all(char in "0123456789abcdef" for char in value)


def evidence_valid(name, expected):
    if not name or not hash_valid(expected):
        return False
    path = (ROOT / name).resolve()
    # Evidence must be deliberately stored in the workspace, not read from a
    # machine's arbitrary private paths. Symlinks outside ROOT fail this check.
    if not path.is_relative_to(ROOT) or not path.is_file():
        return False
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest() == expected


def validate(complete=False, initialize=False):
    missing = []
    devices = read_csv(LEDGER / "beta-devices.csv", ["cohort", "device_id", "api_level", "candidate_apk_sha256"])
    if {row["cohort"] for row in devices} != COHORTS or len(devices) != len(COHORTS):
        raise ValueError("Device cohorts must cover the nine required slots exactly once")
    scenarios = read_csv(LEDGER / "beta-scenarios.csv", ["id", "scenario", "expected", "paired"])
    scenario_map = {row["id"]: row for row in scenarios}
    if len(scenario_map) != len(scenarios) or any(row["paired"] not in {"true", "false"} for row in scenarios):
        raise ValueError("Duplicate scenario ID or invalid paired flag")
    if set(scenario_map) != SCENARIOS:
        raise ValueError("Required scenario coverage cannot be removed or renamed")
    if any((row["paired"] == "true") != (row["id"] not in SINGLE_DEVICE) for row in scenarios):
        raise ValueError("Required paired-device scenarios cannot be downgraded")
    matrix_path = LEDGER / "beta-results.csv"
    if initialize and not matrix_path.exists():
        with matrix_path.open("x", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=RESULT_FIELDS)
            writer.writeheader()
            for device in devices:
                for scenario in scenarios:
                    writer.writerow(dict(cohort=device["cohort"], scenario_id=scenario["id"], status="NOT_RUN"))
    if matrix_path.exists():
        results = read_csv(matrix_path, RESULT_FIELDS)
    else:
        results = []
        missing.append("Beta result matrix not initialized")
    expected = {(cohort, identifier) for cohort in COHORTS for identifier in scenario_map}
    actual = [(row["cohort"], row["scenario_id"]) for row in results]
    if len(set(actual)) != len(actual) or any(item not in expected for item in actual):
        raise ValueError("Duplicate or unknown beta matrix row")
    if expected != set(actual):
        missing.append(f"Missing beta matrix cases: {len(expected - set(actual))}")
    device_map = {row["cohort"]: row for row in devices}
    for row in devices:
        if not all(row.get(field, "").strip() for field in ["device_id", "manufacturer", "model", "api_level", "os_build", "ram_mb"]):
            missing.append(f"Unrecorded device: {row['cohort']}")
        elif not row["api_level"].isdigit() or int(row["api_level"]) < 26 or not row["ram_mb"].isdigit():
            raise ValueError(f"Invalid supported-device properties: {row['cohort']}")
        if row["cohort"] == "oldest_supported" and row["api_level"] != "26":
            missing.append("Oldest supported API 26 not evidenced")
        if not hash_valid(row["candidate_apk_sha256"]):
            missing.append(f"Candidate hash missing: {row['cohort']}")
    for row in results:
        key = f"{row['cohort']}/{row['scenario_id']}"
        if row["status"] not in {"NOT_RUN", "BLOCKED", "FAIL", "PASS"}:
            raise ValueError(f"Invalid case status: {key}")
        if row["status"] != "PASS":
            missing.append(f"Case not passed: {key} ({row['status']})")
            continue
        required = ["candidate_apk_sha256", "network_conditions", "observed_at_utc", "observer"]
        if not all(row[field].strip() for field in required):
            missing.append(f"Incomplete case provenance: {key}")
        if row["candidate_apk_sha256"] != device_map[row["cohort"]]["candidate_apk_sha256"]:
            missing.append(f"Case tested a different candidate: {key}")
        if scenario_map[row["scenario_id"]]["paired"] == "true":
            known = {item["device_id"] for item in devices if item["device_id"]}
            if row["paired_device_id"] not in known or row["paired_device_id"] == device_map[row["cohort"]]["device_id"]:
                missing.append(f"Distinct paired device not recorded: {key}")
        if not evidence_valid(row["evidence_path"], row["evidence_sha256"]):
            missing.append(f"Evidence absent or hash mismatch: {key}")
    review = json.loads((LEDGER / "external-review.json").read_text(encoding="utf-8"))
    if review.get("schema_version") != 1 or review.get("status") not in {"NOT_STARTED", "IN_PROGRESS", "COMPLETE"}:
        raise ValueError("Invalid external review schema/status")
    if not (review.get("status") == "COMPLETE" and review.get("reviewer") and review.get("candidate_source")
            and hash_valid(review.get("candidate_apk_sha256", "")) and review.get("penetration_test_complete") is True
            and review.get("independent_signoff") is True and SURFACES.issubset(set(review.get("covered_surfaces", [])))
            and evidence_valid(review.get("report_path", ""), review.get("report_sha256", ""))):
        missing.append("Independent review / penetration test / exact-candidate sign-off incomplete")
    for row in devices:
        if hash_valid(row["candidate_apk_sha256"]) and row["candidate_apk_sha256"] != review.get("candidate_apk_sha256"):
            missing.append(f"Beta candidate differs from independently assessed APK: {row['cohort']}")
    for name in ["audit-findings.csv", "beta-findings.csv"]:
        findings = read_csv(LEDGER / name, ["id", "severity", "surface", "status", "fix_source", "regression_evidence", "independent_retest"])
        if len({row["id"] for row in findings}) != len(findings):
            raise ValueError(f"Duplicate finding ID: {name}")
        for row in findings:
            if not row["id"] or row["severity"] not in {"CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO", "UNTRIAGED"}:
                raise ValueError(f"Invalid finding: {name}")
            if row["status"] not in {"OPEN", "FIXED", "RETESTED", "ACCEPTED"}:
                raise ValueError(f"Invalid finding status: {row['id']}")
            if row["severity"] in {"CRITICAL", "HIGH", "UNTRIAGED"}:
                if row["severity"] == "UNTRIAGED" or row["status"] != "RETESTED" or not row["fix_source"] or not row["regression_evidence"]:
                    missing.append(f"Release-blocking finding unresolved: {row['id']}")
                elif name == "audit-findings.csv" and row["independent_retest"] != "true":
                    missing.append(f"Independent retest missing: {row['id']}")
            elif row["status"] not in {"RETESTED", "ACCEPTED"}:
                missing.append(f"Finding disposition incomplete: {row['id']}")
    print(f"Ledger structure valid: {len(devices)} device cohorts, {len(scenarios)} scenarios, {len(results)} case records")
    print(f"Readiness checks pending: {len(missing)}")
    if missing:
        for item in missing[:12]:
            print(f"  - {item}")
        if len(missing) > 12:
            print(f"  ... {len(missing) - 12} additional pending checks")
    print("Recorded evidence is not independent verification of human test execution.")
    return 1 if complete and missing else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-complete", action="store_true")
    parser.add_argument("--init-beta-results", action="store_true")
    args = parser.parse_args()
    try:
        raise SystemExit(validate(args.require_complete, args.init_beta_results))
    except (ValueError, OSError, json.JSONDecodeError) as error:
        parser.exit(2, f"Invalid ledger: {error}\n")
