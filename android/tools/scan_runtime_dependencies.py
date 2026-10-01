"""Query OSV for the resolved Maven runtime graph; fail on findings or scan errors.

Only public dependency coordinates/versions are sent to api.osv.dev. No source,
credentials, device identifiers or application data are submitted.
"""
import argparse
import hashlib
import json
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
API = "https://api.osv.dev/v1/querybatch"


def request(queries):
    req = urllib.request.Request(API, data=json.dumps({"queries": queries}).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=60) as response:
        result = json.load(response)["results"]
    if len(result) != len(queries):
        raise ValueError("OSV response did not match query count")
    return result


def scan(components):
    packages = sorted({(c["group"] + ":" + c["name"], c["version"])
                       for c in components if c.get("purl", "").startswith("pkg:maven/")})
    records = []
    for offset in range(0, len(packages), 100):
        batch = packages[offset:offset + 100]
        queries = [{"package": {"ecosystem": "Maven", "name": name}, "version": version}
                   for name, version in batch]
        answers = request(queries)
        for (name, version), query, answer in zip(batch, queries, answers):
            ids = {v["id"] for v in answer.get("vulns", [])}
            seen = set()
            while answer.get("next_page_token"):
                token = answer["next_page_token"]
                if token in seen:
                    raise ValueError("OSV pagination repeated a token")
                seen.add(token)
                answer = request([{**query, "page_token": token}])[0]
                ids.update(v["id"] for v in answer.get("vulns", []))
            records.append({"package": name, "version": version, "vulnerability_ids": sorted(ids)})
    if not records:
        raise ValueError("No resolved Maven components found")
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbom", type=Path, default=ROOT / "android/app/build/reports/security/sbom.cdx.json")
    parser.add_argument("--output", type=Path, default=ROOT / "android/build/phase21/osv-runtime-scan.json")
    args = parser.parse_args()
    report = {"schema_version": 1, "scanned_at_utc": datetime.now(timezone.utc).isoformat(),
              "api": API, "status": "ERROR", "limitations": [
                  "Known OSV Maven advisories only; absence of findings is not a security guarantee",
                  "Embedded native Tor, WebRTC and SQLCipher libraries need separate native inventory/scan",
                  "Does not replace independent review, CI Grype scan or build-tool/plugin scanning"]}
    try:
        payload = args.sbom.read_bytes()
        report["sbom_sha256"] = hashlib.sha256(payload).hexdigest()
        report["packages"] = scan(json.loads(payload)["components"])
        report["affected_package_count"] = sum(bool(p["vulnerability_ids"]) for p in report["packages"])
        report["status"] = "FINDINGS" if report["affected_package_count"] else "NO_KNOWN_MAVEN_FINDINGS"
    except Exception as error:
        # Avoid echoing server bodies or arbitrary local paths.
        report["error_type"] = type(error).__name__
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"{report['status']}: {len(report.get('packages', []))} Maven packages; "
          f"{report.get('affected_package_count', 'unknown')} affected; report: {args.output}")
    return 0 if report["status"] == "NO_KNOWN_MAVEN_FINDINGS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
