"""Bounded real-device checks using ADB. Preserves app data and restores radios."""
import argparse
import hashlib
import json
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.torxone.app"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--network-cycles", action="store_true")
    args = parser.parse_args()
    directory = ROOT / "android/build/device-beta" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    directory.mkdir(parents=True)
    results = []

    def adb(*command, timeout=30):
        return subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)

    def shell(*command, timeout=30):
        result = adb("shell", *command, timeout=timeout)
        return result.stdout + result.stderr

    def record(name, status, detail):
        results.append(dict(check=name, status=status, detail=detail))
        print(f"{status}: {name}: {detail}", flush=True)
        save()

    def save():
        report = dict(schema_version=1, device_id_hash=hashlib.sha256(args.serial.encode()).hexdigest(),
                      generated_at_utc=datetime.now(timezone.utc).isoformat(), checks=results,
                      scope="Supplemental one-device engineering test; not independent penetration assessment",
                      pending=["Two-phone delivery/ACK/calls", "Multi-vendor matrix", "1GB transfer", "Long offline periods", "Independent external review"])
        (directory / "results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    print(f"Evidence directory: {directory}", flush=True)
    if adb("get-state").stdout.strip() != "device":
        raise RuntimeError("Device is not authorized")
    record("DEVICE", "PASS", f"Model={shell('getprop', 'ro.product.model').strip()}; API={shell('getprop', 'ro.build.version.sdk').strip()}")
    apk = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
    candidate_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    record("CANDIDATE", "INFO", f"Debug APK SHA256={candidate_hash}; source correspondence not attested")
    installed = shell("pm", "path", PACKAGE).strip().splitlines()
    base = next((line.removeprefix("package:") for line in installed if line.endswith("/base.apk")), "")
    if re.fullmatch(r"/data/app/[A-Za-z0-9_~=/.-]+/base\.apk", base):
        installed_hash = shell("sha256sum", base).split()[0]
        record("INSTALLED_APK_MATCH", "PASS" if installed_hash == candidate_hash else "FAIL",
               "Installed base APK SHA256 compared to tested host candidate")
    else:
        record("INSTALLED_APK_MATCH", "INCONCLUSIVE", "Could not safely identify installed base APK")
    package_info = shell("dumpsys", "package", PACKAGE)
    flags = re.search(r"\bflags=\[([^\]]+)\]", package_info)
    record("BACKUP_DISABLED", "PASS" if flags and "ALLOW_BACKUP" not in flags[1] else "INCONCLUSIVE",
           "Package manager backup flag checked; debug app remains inspectable through authorized ADB")

    # No raw application logs or databases are copied into this evidence bundle.
    for component in [".service.TorXCoreService", ".calls.TorXCallService"]:
        output = shell("am", "startservice", "-n", PACKAGE + "/" + component)
        denied = "Permission Denial" in output or "not exported" in output.lower()
        record("NONEXPORTED_" + component, "PASS" if denied else "INCONCLUSIVE",
               "Shell start denied" if denied else "No explicit denial proof; requires additional review")
    output = shell("content", "read", "--uri", "content://com.torxone.app.media/received_media/../../databases/torxone.db")
    denied = "Permission Denial" in output or "not exported" in output.lower() or "SecurityException" in output
    record("PROVIDER_TRAVERSAL", "PASS" if denied else "INCONCLUSIVE",
           "Untrusted shell access denied" if denied else "No explicit access denial proof")

    print("Running Android instrumentation suite (fixture databases and UI; no app data clearing)...", flush=True)
    result = adb("shell", "am", "instrument", "-w", "-r",
                 PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=900)
    output = result.stdout + result.stderr
    success = re.search(r"OK \((\d+) tests?\)", output)
    failures = []
    # Keep only class/method identifiers and stack locations, not app logs or test values.
    current = {}
    for line in output.splitlines():
        match = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line)
        if match:
            current[match[1]] = match[2]
        if line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            code = int(line.split(":", 1)[1])
            if code in {-1, -2}:
                failures.append(dict(current))
            current = {}
    locations = [line.strip() for line in output.splitlines() if re.match(r"\s*at com\.torxone\.", line)][:40]
    (directory / "instrumentation.json").write_text(json.dumps(dict(passed=int(success[1]) if success else None,
        failures=failures, stack_locations=locations, runner_exit=result.returncode), indent=2), encoding="utf-8")
    record("ANDROID_SUITE", "PASS" if success and not failures else "FAIL",
           f"{success[1]} tests passed" if success and not failures else f"Runner failure; {len(failures)} failing test identifiers recorded")

    def launch():
        output = shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        time.sleep(3)
        return "Status: ok" in output and bool(shell("pidof", PACKAGE).strip())

    record("FOREGROUND_LAUNCH", "PASS" if launch() else "FAIL", "Launch and process presence checked")
    for index in range(2):
        shell("am", "force-stop", PACKAGE)
        record(f"FORCE_STOP_RELAUNCH_{index + 1}", "PASS" if launch() else "FAIL",
               "Relaunch without clearing data; message/ratchet persistence not proven by process presence")
    trim = shell("am", "send-trim-memory", PACKAGE, "RUNNING_LOW")
    record("MEMORY_CALLBACK", "PASS" if "Error" not in trim and shell("pidof", PACKAGE).strip() else "INCONCLUSIVE",
           "Synthetic trim callback only; not physical RAM exhaustion")

    if args.network_cycles:
        def wait_state(state, expected):
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                if state() == expected:
                    return True
                time.sleep(1)
            return False

        wifi = "Wifi is enabled" in shell("cmd", "wifi", "status")
        bluetooth = shell("settings", "get", "global", "bluetooth_on").strip() == "1"
        airplane = shell("cmd", "connectivity", "airplane-mode").strip() == "enabled"
        try:
            for name, before, change, restore, state in [
                ("WIFI", wifi, ("cmd", "wifi", "set-wifi-enabled", "disabled" if wifi else "enabled"),
                 ("cmd", "wifi", "set-wifi-enabled", "enabled" if wifi else "disabled"),
                 lambda: "Wifi is enabled" in shell("cmd", "wifi", "status")),
                ("BLUETOOTH", bluetooth, ("cmd", "bluetooth_manager", "disable" if bluetooth else "enable"),
                 ("cmd", "bluetooth_manager", "enable" if bluetooth else "disable"),
                 lambda: shell("settings", "get", "global", "bluetooth_on").strip() == "1"),
                ("AIRPLANE", airplane, ("cmd", "connectivity", "airplane-mode", "disable" if airplane else "enable"),
                 ("cmd", "connectivity", "airplane-mode", "enable" if airplane else "disable"),
                 lambda: shell("cmd", "connectivity", "airplane-mode").strip() == "enabled")]:
                shell(*change)
                changed = wait_state(state, not before)
                shell(*restore)
                restored = wait_state(state, before)
                status = "PASS" if changed and restored and shell("pidof", PACKAGE).strip() else "INCONCLUSIVE"
                record(name + "_CYCLE", status,
                       "Actual radio state toggled/restored and process alive; peer delivery remains untested")
        finally:
            shell("cmd", "connectivity", "airplane-mode", "enable" if airplane else "disable")
            shell("cmd", "wifi", "set-wifi-enabled", "enabled" if wifi else "disabled")
            shell("cmd", "bluetooth_manager", "enable" if bluetooth else "disable")
            time.sleep(3)
            restored = ("Wifi is enabled" in shell("cmd", "wifi", "status")) == wifi and (
                shell("settings", "get", "global", "bluetooth_on").strip() == "1") == bluetooth and (
                shell("cmd", "connectivity", "airplane-mode").strip() == "enabled") == airplane
            record("RADIO_RESTORE", "PASS" if restored else "FAIL", "Original Wi-Fi Bluetooth and airplane states checked")
    save()
    print("Completed supplemental device checks; release and independent-review gates remain open.", flush=True)
    return 1 if any(row["status"] == "FAIL" for row in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
