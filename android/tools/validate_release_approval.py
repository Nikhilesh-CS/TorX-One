"""Check Phase 21 sign-off metadata and hashed evidence; never supply human approval."""
import argparse
import json
from datetime import datetime

import validate_release_evidence as ledger

CHECKS = {"migrations", "unit_tests", "android_tests", "lint", "optimized_device",
          "native_dependencies", "build_tool_dependencies", "signing_upgrade", "store_metadata",
          "privacy_claims", "threat_model", "accessibility", "repository_protection"}


def validate(complete=False, apk_hash=None, certificate=None, version_name=None, version_code=None):
    approval = json.loads((ledger.LEDGER / "release-approval.json").read_text(encoding="utf-8"))
    if approval.get("schema_version") != 1 or approval.get("status") not in {"NOT_APPROVED", "IN_REVIEW", "APPROVED"}:
        raise ValueError("Invalid release approval schema or status")
    checks = approval.get("checks", [])
    identifiers = [row["id"] for row in checks]
    if len(set(identifiers)) != len(identifiers) or set(identifiers) != CHECKS:
        raise ValueError("All mandatory Phase 21 checks must appear exactly once")
    if not isinstance(approval.get("version_code"), int) or approval["version_code"] < 1 or not approval.get("version_name"):
        raise ValueError("Invalid release version metadata")
    pending = []
    if approval["status"] != "APPROVED":
        pending.append("Human release approval absent")
    if not all(approval.get(key) for key in ["candidate_source", "reviewer", "reviewed_at_utc"]):
        pending.append("Release reviewer or source/date provenance absent")
    if approval.get("reviewed_at_utc"):
        when = datetime.fromisoformat(approval["reviewed_at_utc"].replace("Z", "+00:00"))
        if when.tzinfo is None:
            raise ValueError("Review time must include its timezone")
    for key in ["candidate_apk_sha256", "certificate_sha256"]:
        if not ledger.hash_valid(approval.get(key, "")):
            pending.append(f"Missing {key}")
    if apk_hash is not None and approval.get("candidate_apk_sha256") != apk_hash:
        pending.append("Actual APK differs from approved release candidate")
    if certificate is not None and approval.get("certificate_sha256") != certificate:
        pending.append("Actual signer differs from approved release certificate")
    if version_name is not None and approval["version_name"] != version_name:
        pending.append("Actual version name differs from approved release version")
    if version_code is not None and approval["version_code"] != version_code:
        pending.append("Actual version code differs from approved release version")
    for row in checks:
        if row.get("status") not in {"NOT_RUN", "BLOCKED", "FAIL", "PASS"}:
            raise ValueError(f"Invalid Phase 21 check status: {row['id']}")
        if row["status"] != "PASS" or not ledger.evidence_valid(row.get("evidence_path", ""), row.get("evidence_sha256", "")):
            pending.append(f"Missing passing hashed evidence: {row['id']}")
    print(f"Phase 21 approval structure valid; pending checks: {len(pending)}")
    for item in pending[:6]:
        print(f"  - {item}")
    print("Human reviewers must establish the truth and candidate scope of each evidence record.")
    return 1 if complete and pending else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()
    try:
        raise SystemExit(validate(args.require_complete))
    except (ValueError, KeyError, TypeError, OSError, json.JSONDecodeError) as error:
        parser.exit(2, f"Invalid release approval: {error}\n")
