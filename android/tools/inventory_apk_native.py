"""Hash native libraries in the actual APK without extraction; this is not a CVE scan."""
import argparse
import hashlib
import json
import re
import zipfile
from datetime import datetime, timezone
from pathlib import Path

MAX_TOTAL_BYTES = 1024 * 1024 * 1024
LIBRARY = re.compile(r"lib/(arm64-v8a|armeabi-v7a|x86|x86_64)/([A-Za-z0-9_.+-]+\.so)")


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def inventory(apk):
    before = digest(apk)
    records, names, total = [], set(), 0
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if not entry.filename.endswith(".so"):
                continue
            match = LIBRARY.fullmatch(entry.filename)
            if not match or entry.filename in names:
                raise ValueError("Unexpected or duplicate native library path; review packaging")
            names.add(entry.filename)
            total += entry.file_size
            if total > MAX_TOTAL_BYTES:
                raise ValueError("Native inventory exceeds its bounded size")
            value = hashlib.sha256()
            with archive.open(entry) as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    value.update(chunk)
            records.append(dict(path=entry.filename, abi=match[1], name=match[2],
                                size=entry.file_size, sha256=value.hexdigest()))
    if not records:
        raise ValueError("No packaged native libraries found")
    if digest(apk) != before:
        raise ValueError("APK changed during native inventory")
    return dict(schema_version=1, generated_at_utc=datetime.now(timezone.utc).isoformat(),
                apk_sha256=before, status="INVENTORIED",
                libraries=sorted(records, key=lambda row: row["path"]),
                limitations=["Hashes identify packaged bytes; upstream versions, provenance and licenses require review",
                             "No native vulnerability scan or absence-of-vulnerabilities claim",
                             "Executables or native code hidden in assets/JARs are outside shared-library coverage"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = inventory(args.apk)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"INVENTORIED: {len(report['libraries'])} native library entries; not a vulnerability scan")
    except (ValueError, OSError, RuntimeError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Native inventory failed: {error}\n")
