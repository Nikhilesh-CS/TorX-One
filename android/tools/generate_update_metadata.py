"""Generate updater assets from an already signed, independently verified APK. Never builds/signs."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

from verify_signed_candidate import verify_package, verify_signer


def configuration(path):
    text = path.read_text(encoding="utf-8-sig")
    codes = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", text, re.MULTILINE)
    names = re.findall(r'^\s*versionName\s*=\s*"([^"\n]+)"\s*$', text, re.MULTILINE)
    sdks = re.findall(r"^\s*minSdk\s*=\s*(\d+)\s*$", text, re.MULTILINE)
    if len(codes) != 1 or len(names) != 1 or len(sdks) != 1:
        raise ValueError("Expected one literal Gradle versionCode, versionName and minSdk")
    code, name, sdk = int(codes[0]), names[0], int(sdks[0])
    if not 1 <= code <= 2147483647 or not 1 <= sdk <= 1000 or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", name):
        raise ValueError("Invalid release configuration")
    return code, name, sdk


def generate(apk, output, gradle, tag, package_report, signature_report, trusted, notes,
             minimum_supported=1, critical=False):
    code, name, sdk = configuration(gradle)
    # Allow either a first semantic release tag or explicitly numbered build tags.
    if tag not in (f"v{name}", f"v{name}-build{code}"):
        raise ValueError("Tag does not match the actual release version")
    actual_name, actual_code = verify_package(package_report)
    if (name, code) != (actual_name, actual_code):
        raise ValueError("APK version disagrees with Gradle; rebuild before publishing")
    verify_signer(signature_report, trusted)
    if not 1 <= minimum_supported <= code:
        raise ValueError("Invalid minimum supported version")
    if len(notes) > 4000:
        raise ValueError("Use concise release notes (maximum 4000 characters)")
    if not apk.is_file() or not 1 <= apk.stat().st_size <= 512 * 1024 * 1024:
        raise ValueError("APK missing or exceeds size policy")
    output.mkdir(parents=True, exist_ok=True)
    filename = f"torxone-v{name}-build{code}.apk"
    target = output / filename
    if apk.resolve() != target.resolve():
        shutil.copyfile(apk, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    metadata = {
        "schemaVersion": 1, "channel": "stable", "versionCode": code, "versionName": name,
        "minimumSupportedVersionCode": minimum_supported, "minimumAndroidSdk": sdk,
        "critical": critical, "apkAsset": filename, "sha256": digest, "releaseNotes": notes,
    }
    encoded = (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(encoded) > 16384:
        raise ValueError("Metadata exceeds app response size policy")
    (output / "update.json").write_bytes(encoded)
    metadata_hash = hashlib.sha256(encoded).hexdigest()
    (output / "SHA256SUMS").write_text(f"{digest}  {filename}\n{metadata_hash}  update.json\n", encoding="utf-8")
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, default=Path("android/app/build.gradle.kts"))
    parser.add_argument("--tag", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--trusted-certificate-sha256", required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--minimum-supported", type=int, default=1)
    parser.add_argument("--critical", action="store_true")
    args = parser.parse_args()
    # Tool outputs are obtained directly from the input APK, never supplied by a caller.
    before = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    signature = subprocess.run([args.apksigner, "verify", "--verbose", "--print-certs", str(args.apk)],
                               check=True, capture_output=True, text=True).stdout
    package = subprocess.run([args.aapt, "dump", "badging", str(args.apk)],
                             check=True, capture_output=True, text=True).stdout
    metadata = generate(args.apk, args.output, args.gradle, args.tag, package, signature,
                        args.trusted_certificate_sha256, args.notes.read_text(encoding="utf-8"),
                        args.minimum_supported, args.critical)
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != before or metadata["sha256"] != before:
        raise ValueError("APK changed while metadata was generated")
    print(f"Generated verified update assets for versionCode {metadata['versionCode']}")


if __name__ == "__main__":
    main()
