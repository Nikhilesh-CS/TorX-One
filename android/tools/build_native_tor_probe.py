"""Build an unsigned framework-only instrumentation APK for an optimized target.

Compiles only NativeTorReadinessInstrumentation.java with local JDK/Android tools.
No signing, device access, Gradle, network, or bundled AndroidX/Kotlin classes.
RESULT_OK from this probe means CAPTURE_ONLY, never Tor readiness or delivery proof.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
SOURCE = TOOLS.parent / "app/src/androidTest/java/com/torxone/app/transport/tor/NativeTorReadinessInstrumentation.java"
MANIFEST = TOOLS / "native_tor_probe/AndroidManifest.xml"
CLASS_NAMES = {
    "NativeTorReadinessInstrumentation.class",
    "NativeTorReadinessInstrumentation$1.class",
    "NativeTorReadinessInstrumentation$2.class",
    "NativeTorReadinessInstrumentation$ProtocolException.class",
}


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def run(stage, command, timeout=120):
    result = subprocess.run([str(part) for part in command], capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        detail = (result.stderr + "\n" + result.stdout).strip()
        raise RuntimeError(f"{stage} failed with exit {result.returncode}: {detail}")


def required(path):
    if not path.is_file():
        raise ValueError(f"Required local tool or input not found: {path}")
    return path


def build(sdk, java_home, output, android_jar=None):
    sdk, java_home, output = sdk.resolve(), java_home.resolve(), output.resolve()
    suffix = ".exe" if os.name == "nt" else ""
    java = required(java_home / "bin" / ("java" + suffix))
    javac = required(java_home / "bin" / ("javac" + suffix))
    if android_jar is None:
        candidates = [sdk / "platforms/android-37.1/android.jar", sdk / "platforms/android-36/android.jar"]
        android_jar = next((path for path in candidates if path.is_file()), None)
        if android_jar is None:
            raise ValueError("Installed Android platform 37.1 or 36 is required, or pass --android-jar")
    android_jar = required(android_jar.resolve())
    versions = [path for path in (sdk / "build-tools").iterdir()
                if path.is_dir() and re.fullmatch(r"36\.\d+\.\d+", path.name)]
    if not versions:
        raise ValueError("Installed Android build-tools 36.x are required")
    build_tools = max(versions, key=lambda path: tuple(map(int, path.name.split("."))))
    aapt2 = required(build_tools / ("aapt2" + suffix))
    zipalign = required(build_tools / ("zipalign" + suffix))
    d8 = required(build_tools / "lib/d8.jar")
    required(SOURCE)
    required(MANIFEST)
    imports = re.findall(r"^import\s+([^;]+);", SOURCE.read_text(encoding="utf-8"), re.MULTILINE)
    if any(not name.startswith(("android.", "java.")) for name in imports):
        raise ValueError("Standalone probe source must import only Android framework and Java classes")
    source_hash, manifest_hash = digest(SOURCE), digest(MANIFEST)
    output.mkdir(parents=True, exist_ok=True)
    unsigned = output / "native-tor-probe-unsigned.apk"
    report_path = output / "native-tor-probe-build.json"
    with tempfile.TemporaryDirectory(prefix="native-tor-probe-", dir=output) as temporary:
        work = Path(temporary).resolve()
        if work.parent != output:
            raise ValueError("Build temporary directory escaped the explicitly selected output")
        classes, dex = work / "classes", work / "dex"
        classes.mkdir()
        dex.mkdir()
        run("javac", [javac, "--release", "17", "-encoding", "UTF-8", "-g:none",
                      "-classpath", android_jar, "-d", classes, SOURCE])
        class_files = sorted(classes.rglob("*.class"))
        if {path.name for path in class_files} != CLASS_NAMES or len(class_files) != 4:
            raise ValueError("Standalone probe must compile to exactly its four owned Java classes")
        expected_parent = classes / "com/torxone/app/transport/tor"
        if any(path.parent != expected_parent for path in class_files):
            raise ValueError("Unexpected package in standalone probe compiled classes")
        run("D8", [java, "-cp", d8, "com.android.tools.r8.D8", "--release",
                   "--min-api", "26", "--lib", android_jar, "--output", dex, *class_files])
        dex_files = list(dex.glob("*.dex"))
        if len(dex_files) != 1 or dex_files[0].name != "classes.dex":
            raise ValueError("Standalone probe must produce exactly one owned classes.dex")
        unaligned = work / "native-tor-probe-unaligned.apk"
        run("aapt2 link", [aapt2, "link", "-I", android_jar, "--manifest", MANIFEST,
                           "--min-sdk-version", "26", "--target-sdk-version", "36", "-o", unaligned])
        with zipfile.ZipFile(unaligned, "a") as archive:
            if not set(archive.namelist()).issubset({"AndroidManifest.xml", "resources.arsc"}):
                raise ValueError("aapt2 produced unexpected code or resources for the minimal probe")
            archive.write(dex_files[0], "classes.dex", compress_type=zipfile.ZIP_STORED)
        aligned = work / "native-tor-probe-unsigned.apk"
        run("zipalign", [zipalign, "-P", "16", "-f", "4", unaligned, aligned])
        run("zipalign verify", [zipalign, "-c", "-P", "16", "4", aligned])
        if digest(SOURCE) != source_hash or digest(MANIFEST) != manifest_hash:
            raise ValueError("Probe source or manifest changed during the standalone build")
        report = {
            "schema_version": 1,
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "package_name": "com.torxone.app.probe",
            "target_package": "com.torxone.app",
            "instrumentation_class": "com.torxone.app.transport.tor.NativeTorReadinessInstrumentation",
            "probe_meaning": "CAPTURE_ONLY",
            "unsigned": True,
            "min_sdk": 26,
            "target_sdk": 36,
            "compile_platform": android_jar.parent.name,
            "android_jar": str(android_jar),
            "android_jar_sha256": digest(android_jar),
            "build_tools_version": build_tools.name,
            "class_files": [path.name for path in class_files],
            "source": str(SOURCE),
            "source_sha256": source_hash,
            "manifest": str(MANIFEST),
            "manifest_sha256": manifest_hash,
            "classes_dex_sha256": digest(dex_files[0]),
            "unsigned_apk": str(unsigned),
            "unsigned_apk_sha256": digest(aligned),
            "alignment": {"uncompressed_bytes": 4, "shared_library_page_kib": 16, "verified": True},
        }
        aligned.replace(unsigned)
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return unsigned, report_path, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, help="Explicit installed compile platform JAR; default prefers Android 37.1, then 36")
    parser.add_argument("--output", type=Path, required=True, help="Directory for unsigned APK and hash report")
    args = parser.parse_args()
    try:
        unsigned, report_path, report = build(args.sdk, args.java_home, args.output, args.android_jar)
        print(f"UNSIGNED CAPTURE_ONLY APK: {unsigned}")
        print(f"SHA-256: {report['unsigned_apk_sha256']}")
        print(f"Build report: {report_path}")
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        parser.exit(1, f"Standalone native Tor probe build failed: {error}\n")


if __name__ == "__main__":
    main()
