"""Create a local, hashed source overlay and bounded evidence summary. No upload."""
import argparse
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def source_files():
    # Explicit source roots. Never recurse through the checkout, SDK, .git, local
    # settings, device logs, databases, credentials or arbitrary review evidence.
    roots = ["android/app/src", "android/app/schemas", "android/tools", ".github/workflows"]
    suffixes = {".kt", ".kts", ".java", ".xml", ".py", ".json", ".yml", ".yaml", ".md"}
    files = set()
    for folder in roots:
        for path in (ROOT / folder).rglob("*"):
            if path.is_file() and path.suffix.lower() in suffixes and not path.is_symlink():
                if not path.resolve().is_relative_to(ROOT):
                    continue
                if any(part in {"build", "__pycache__", ".gradle"} for part in path.relative_to(ROOT).parts):
                    continue
                if path.stat().st_size > 8 * 1024 * 1024:
                    raise ValueError(f"Review unexpectedly large source file: {path.relative_to(ROOT)}")
                files.add(path)
    files.update(path for path in (ROOT / "docs").glob("*.md") if not path.is_symlink())
    # Ledger metadata is needed by the gate scripts; external reports and raw
    # evidence are intentionally not bundled. Review metadata before sharing.
    files.update(path for path in (ROOT / "docs/validation").glob("*")
                 if path.is_file() and path.suffix in {".csv", ".json"} and not path.is_symlink())
    for name in ["README.md", "SECURITY.md", ".gitignore", ".github/dependabot.yml", "android/build.gradle.kts",
                 "android/settings.gradle.kts", "android/app/build.gradle.kts", "android/app/proguard-rules.pro",
                 "android/gradlew", "android/gradlew.bat", "android/gradle/wrapper/gradle-wrapper.properties",
                 "android/gradle/wrapper/gradle-wrapper.jar"]:
        path = ROOT / name
        if path.is_file() and not path.is_symlink():
            files.add(path)
    return sorted(files, key=lambda path: path.relative_to(ROOT).as_posix())


def unit_summary():
    result = dict(tests=0, failures=0, errors=0, skipped=0, report_count=0)
    for report in sorted((ROOT / "android/app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")):
        suite = ET.parse(report).getroot()
        result["report_count"] += 1
        for field in ["tests", "failures", "errors", "skipped"]:
            result[field] += int(suite.get(field, "0"))
    result["scope"] = "Existing local reports only; not independent review or device evidence"
    return result


def prepare():
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    output = ROOT / "android/build/review" / stamp
    output.mkdir(parents=True, exist_ok=False)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    state = subprocess.check_output(["git", "status", "--porcelain=v1", "--untracked-files=all"], cwd=ROOT, text=True)
    records = []
    archive = output / "source-overlay.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for path in source_files():
            relative = path.relative_to(ROOT).as_posix()
            # Hash the exact bytes placed in the archive, even if an editor saves
            # between reads. Reviewers can verify these bytes independently.
            payload = path.read_bytes()
            records.append(dict(path=relative, size=len(payload), sha256=hashlib.sha256(payload).hexdigest()))
            bundle.writestr(relative, payload)
    fingerprint = hashlib.sha256(json.dumps(records, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    artifacts = []
    for name in ["android/app/build/outputs/apk/debug/app-debug.apk",
                 "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
                 "android/app/build/outputs/apk/release/app-release-unsigned.apk",
                 "android/app/build/outputs/apk/release/app-release.apk",
                 "android/app/build/outputs/mapping/release/mapping.txt",
                 "android/build/release-repair-validation.json",
                 "android/build/release-repair/signed-candidate-verification.json",
                 "android/build/release-repair/signing-identity.json",
                 "android/build/release-repair/signing-certificate.der",
                 "android/build/release-repair/tor-jni-contract.json",
                 "android/build/device-validation/latest.json",
                 "android/build/device-validation/20261003/native-framework-probe/native-tor-probe.apk",
                 "android/build/device-validation/20261003/native-framework-probe/native-tor-probe-build.json",
                 "android/build/release-repair/osv-runtime-scan.json",
                 "android/build/release-repair/native-libraries.json",
                 "android/build/phase21/osv-runtime-scan.json",
                 "android/build/phase21/native-libraries.json",
                 "android/build/phase21/local-validation.json"]:
        path = ROOT / name
        if path.is_file():
            artifacts.append(dict(path=name, size=path.stat().st_size, sha256=digest(path),
                                  source_correspondence="NOT_ATTESTED",
                                  signing="Unsigned optimized APK; not distributable" if name.endswith("app-release-unsigned.apk")
                                  else "Release signing identity; verify recorded certificate separately" if name.endswith("app-release.apk")
                                  else "Framework test instrumentation; CAPTURE_ONLY; verify certificate separately" if name.endswith("native-tor-probe.apk")
                                  else "Debug artifact; verify certificate separately" if name.endswith(".apk")
                                  else "Not a signed application artifact"))
    manifest = dict(schema_version=1, generated_at_utc=stamp, base_commit=head,
                    working_tree_clean=not bool(state.strip()), working_tree_status=state.splitlines(),
                    source_fingerprint=fingerprint, source_files=records,
                    overlay_sha256=digest(archive), artifacts=artifacts, unit_reports=unit_summary(),
                    independent_security_review="NOT_STARTED", closed_beta="NOT_COMPLETED",
                    limitations=["Overlay requires the recorded base checkout and build dependencies.",
                                 "APK hashes do not prove correspondence with the overlay.",
                                 "Raw logs, databases, local settings, key files and external assessment reports excluded.",
                                 "Unit reports are an observed local aggregate; rerun against the reviewed candidate.",
                                 "No independent assessment, completed mandatory physical-device beta matrix or release sign-off supplied."])
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (output / "SHA256SUMS").write_text(
        f"{digest(archive)}  source-overlay.zip\n{digest(output / 'manifest.json')}  manifest.json\n", encoding="utf-8")
    print(f"Prepared local review package: {output}")
    print(f"Source files: {len(records)}; unit reports: {manifest['unit_reports']['tests']} tests")
    print("Independent review and beta completion remain pending. Nothing uploaded.")


if __name__ == "__main__":
    argparse.ArgumentParser(description=__doc__).parse_args()
    prepare()
