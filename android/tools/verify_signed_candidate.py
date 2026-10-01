"""Verify the actual signed APK, trusted signer and (for 1.0) approved candidate hash."""
import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import validate_release_evidence as ledger
import validate_release_approval as approval


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def fingerprint(value):
    result = value.replace(":", "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", result):
        raise ValueError("A trusted signing certificate SHA-256 fingerprint is required")
    return result


def verify_signer(report, trusted):
    trusted = fingerprint(trusted)
    if not re.search(r"^Verifies\s*$", report, re.MULTILINE):
        raise ValueError("APK signature verification did not succeed")
    if not re.search(r"^Verified using v(?:2|3|3\.1) scheme[^\n]*: true\s*$", report, re.MULTILINE):
        raise ValueError("APK must have a verified v2 or newer signature")
    if not re.search(r"^Number of signers: 1\s*$", report, re.MULTILINE):
        raise ValueError("Review multiple signers before changing this release policy")
    matches = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)\s*$", report, re.MULTILINE)
    if len(matches) != 1 or fingerprint(matches[0]) != trusted:
        raise ValueError("APK signer differs from the independently trusted fingerprint")
    if re.search(r"^Signer #\d+ certificate DN:.*CN\s*=\s*Android Debug(?:\s|,|$)", report, re.MULTILINE | re.IGNORECASE):
        raise ValueError("Android debug certificates are not production release certificates")
    return trusted


def verify_approved_hash(apk_hash):
    review = json.loads((ledger.LEDGER / "external-review.json").read_text(encoding="utf-8"))
    if review.get("candidate_apk_sha256") != apk_hash:
        raise ValueError("Built signed APK differs from the independently approved candidate")
    for device in ledger.read_csv(ledger.LEDGER / "beta-devices.csv", ["candidate_apk_sha256"]):
        if device["candidate_apk_sha256"] != apk_hash:
            raise ValueError("Built signed APK differs from the beta-tested candidate")


def verify_package(report):
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", report, re.MULTILINE)
    if not package or package[1] != "com.torxone.app":
        raise ValueError("Actual APK has an unexpected package or missing version metadata")
    if re.search(r"^application-debuggable\s*$", report, re.MULTILINE):
        raise ValueError("Debuggable APKs are not production release candidates")
    return package[3], int(package[2])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--trusted-certificate-sha256", required=True)
    parser.add_argument("--require-approved-ledger", action="store_true")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    # Execute the verifier on this APK; a supplied report cannot attest unrelated bytes.
    try:
        trusted = fingerprint(args.trusted_certificate_sha256)
        before = digest(args.apk)
        process = subprocess.run([args.apksigner, "verify", "--verbose", "--print-certs", str(args.apk)],
                                 capture_output=True, text=True, timeout=120)
        if process.returncode != 0:
            raise ValueError("apksigner rejected the actual APK")
        certificate = verify_signer(process.stdout, trusted)
        metadata = subprocess.run([args.aapt, "dump", "badging", str(args.apk)], capture_output=True, text=True, timeout=120)
        if metadata.returncode != 0:
            raise ValueError("Unable to verify the actual APK package metadata")
        version_name, version_code = verify_package(metadata.stdout)
        apk_hash = digest(args.apk)
        if apk_hash != before:
            raise ValueError("APK changed during signature verification")
        if args.require_approved_ledger:
            if ledger.validate(complete=True) != 0:
                raise ValueError("Independent review and mandatory beta ledger are incomplete")
            verify_approved_hash(apk_hash)
            if approval.validate(complete=True, apk_hash=apk_hash, certificate=certificate,
                                 version_name=version_name, version_code=version_code) != 0:
                raise ValueError("Final Phase 21 review is incomplete or approved a different artifact")
        report = dict(schema_version=1, verified_at_utc=datetime.now(timezone.utc).isoformat(),
                      apk_sha256=apk_hash, certificate_sha256=certificate,
                      package="com.torxone.app", version_name=version_name, version_code=version_code,
                      approved_ledger_required=args.require_approved_ledger,
                      status="VERIFIED", limitations=["Human reviewers must confirm the truth of assessment and beta records",
                                                       "Distribution and installed upgrade behavior need separate checks"])
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"VERIFIED: signed candidate SHA256={apk_hash}")
        return 0
    except (ValueError, OSError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        parser.exit(1, f"Release candidate rejected: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
