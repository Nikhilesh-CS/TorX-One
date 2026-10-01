"""Bounded parser probes against the connected phone's own debug Tor listener.

Uses ADB-local forwarding, not the Tor network. No contacts/keys/messages are read.
"""
import argparse
import json
import os
import re
import socket
import struct
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    output = ROOT / "android/build/device-beta" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    output.mkdir(parents=True)
    results = []
    forwarded = None

    def adb(*command, data=None, timeout=15):
        return subprocess.run([args.adb, "-s", args.serial, *command], input=data,
            text=True, capture_output=True, encoding="utf-8", errors="replace", timeout=timeout)

    def record(name, status, detail):
        results.append(dict(check=name, status=status, detail=detail))
        (output / "tor-probes.json").write_text(json.dumps(dict(scope="Own device; ADB-local debug listener only",
            checks=results, generated_at_utc=datetime.now(timezone.utc).isoformat()), indent=2), encoding="utf-8")
        print(f"{status}: {name}: {detail}", flush=True)

    print(f"Evidence directory: {output}", flush=True)
    response = adb("shell", "run-as", "com.torxone.app", "toybox", "nc", "-U", "-w", "3", "-q", "3",
        "/data/user/0/com.torxone.app/app_TorService/data/ControlSocket",
        data="AUTHENTICATE\r\nGETCONF HiddenServicePort\r\nGETINFO status/bootstrap-phase\r\nQUIT\r\n", timeout=12)
    match = re.search(r"HiddenServicePort=17654 127\.0\.0\.1:(\d+)", response.stdout)
    progress = re.search(r"PROGRESS=(\d+)", response.stdout)
    if not match:
        record("LISTENER", "BLOCKED", "No live hidden-service listener discovered; unlock app and start Tor")
        return 2
    record("TOR_BOOTSTRAP", "PASS" if progress and progress[1] == "100" else "INCONCLUSIVE",
           f"Reported bootstrap={progress[1] if progress else 'unknown'}; does not prove peer delivery")
    try:
        forwarded = adb("forward", "tcp:0", f"tcp:{match[1]}").stdout.strip()
        port = int(forwarded)

        def connect():
            return socket.create_connection(("127.0.0.1", port), timeout=3)

        def ping():
            with connect() as sock:
                nonce = os.urandom(16)
                sock.sendall(struct.pack("!I", 0x54585031) + nonce)
                value = b""
                while len(value) < 20:
                    chunk = sock.recv(20 - len(value))
                    if not chunk:
                        return False
                    value += chunk
                return value == struct.pack("!I", 0x54585032) + nonce

        record("LOCAL_PING_BEFORE", "PASS" if ping() else "FAIL", "Debug nonce round-trip on own local listener")
        onion = ("a" * 56 + ".onion").encode()
        header = struct.pack("!H", len(onion)) + onion
        probes = [b"\x00", b"\x00\x03bad", header + struct.pack("!i", 0),
                  header + struct.pack("!i", -1), header + struct.pack("!i", 2147483647),
                  header + b"\x00\x01", header + struct.pack("!i", 5) + b"x"]
        probes += [header + struct.pack("!i", size) + bytes([0xA5]) * size for size in [1, 4, 16, 64, 256]]
        rejected = 0
        for payload in probes:
            try:
                with connect() as sock:
                    sock.sendall(payload); sock.shutdown(socket.SHUT_WR)
                    rejected += sock.recv(1) == b""
            except ConnectionResetError:
                rejected += 1
            time.sleep(0.05)
        record("MALFORMED_FRAMES", "PASS" if rejected == len(probes) else "INCONCLUSIVE",
               f"{rejected}/{len(probes)} malformed or unauthenticated connections closed; no authenticated ratchet coverage")
        record("LOCAL_PING_AFTER", "PASS" if ping() else "FAIL", "Listener still answers after malformed input")
        clients = []
        try:
            for _ in range(34):
                clients.append(connect())
            time.sleep(0.5)
        finally:
            for client in clients:
                client.close()
        time.sleep(0.5)
        record("PARTIAL_CONNECTION_RECOVERY", "PASS" if ping() else "FAIL",
               "Listener recovered after 34 bounded partial connections were closed; not a sustained DoS assessment")
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        record("PROBE_ERROR", "FAIL", type(error).__name__)
    finally:
        if forwarded:
            adb("forward", "--remove", f"tcp:{forwarded}")
    return 1 if any(item["status"] == "FAIL" for item in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
