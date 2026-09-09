import subprocess
import os
import sys

NOTES = """## What's New in TorX One v1.0.27

### 🔐 Call Privacy Architecture & Security Hardening (Phase 5)
- **Strict 3-Tier Call Privacy Policy**:
  - `NORMAL`: Host + srflx + relay allowed; RFC1918/ULA private LAN IPs (`192.168.x`, `10.x`, `172.16-31.x`) stripped before signaling.
  - `PRIVACY`: 100% of host candidates blocked to prevent local network topology exposure; only configured privacy STUN and TURN relays permitted.
  - `STRICT`: Relay-only mode (`IceTransportsType.RELAY`). All host and srflx candidates completely withheld, ensuring media flows exclusively via TURN relay.
- **Removed Third-Party STUN Telemetry**: Stripped all hardcoded Google STUN servers (`stun.l.google.com:19302`) to prevent third-party IP, ISP, and call timing leakage.
- **Signaling Security & Collision Defenses**: Enforced 64KB signal payload limit, 64KB SDP ceiling, 4KB ICE candidate limit, strict schema validation, sender public key verification, and busy collision protection returning `"Busy"` when in an active call.
- **Structured Security Event Logging**: Implemented `CallSecurityLogger` with peer public key redaction (`key.take(12)...`) and zero raw SDP/IP address leakage into system logs.
- **Notification Privacy**: Set `VISIBILITY_PRIVATE` on ongoing call notifications.

### 📶 Call Quality, Network Handover & Audio Lifecycle (Phases 1–4)
- **Real-Time Quality Monitoring**: Periodic WebRTC stats polling (RTT, packet loss %, jitter, bitrate, available bandwidth) with rolling window evaluation (Excellent, Good, Poor, Critical) and real-time In-Call UI status pills.
- **Seamless Network Handover**: Network interface listener with 1.5s debounce automatically triggers non-disruptive ICE restart renegotiation when transitioning between Wi-Fi and Cellular data, preventing silent call drops.
- **Dedicated Audio Lifecycle Manager**: Telephony audio focus coordination handling transient losses (voice notes, alarms, transient calls) by ducking or pausing playback without altering user mic mute state, restoring full audio when focus returns.

### 🧅 Native Embedded Tor v0.4.9.9
- **Modern 16KB Page Aligned Binaries**: Packaged modern PIE ELF binaries for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, eliminating the 16KB page alignment warning on Android 15+.
- **Zero Daemon Leaks**: Automated process lifecycle management and socket teardown.

### 🧪 Verification & Hardware Testing
- **Unit Test Suite**: 73/73 tests passing (100% success rate).
- **On-Device Hardware Verification**: All 6 on-device hardware privacy tests verified on physical device (Realme RMX5070 - Android 16 / API 36).

### 📦 Checksums & Artifacts
- **File**: `TorX-One-v1.0.27-release.apk`
- **Size**: 43.65 MB (43,650,290 bytes)
- **SHA-256**: `205A3C52B96232675F0792214952A8E1B4C3BB3E937D15E962209F22BAB171CA`
"""

def get_gh_token():
    proc = subprocess.Popen(['git', 'credential', 'fill'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, err = proc.communicate(input="url=https://github.com\n")
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("password=", 1)[1].strip()
    return None

def main():
    token = get_gh_token()
    if not token:
        print("Error: Could not retrieve token from git credentials")
        sys.exit(1)
        
    env = os.environ.copy()
    env["GH_TOKEN"] = token
    
    notes_file = "release_notes_v1.0.27.md"
    with open(notes_file, "w", encoding="utf-8") as f:
        f.write(NOTES)
        
    apk_path = "TorX-One-v1.0.27-release.apk"
    if not os.path.exists(apk_path):
        print(f"Error: {apk_path} not found")
        sys.exit(1)
        
    cmd = [
        "gh", "release", "create", "v1.0.27",
        apk_path,
        "--title", "TorX One v1.0.27 - Call Quality, Reconnection & Privacy Hardening",
        "--notes-file", notes_file
    ]
    
    print("Running:", " ".join(cmd))
    res = subprocess.run(cmd, env=env, capture_output=True, text=True)
    print("STDOUT:", res.stdout)
    print("STDERR:", res.stderr)
    
    if res.returncode == 0:
        print("Release v1.0.27 created successfully!")
    else:
        print("Failed to create release, returncode:", res.returncode)
        sys.exit(res.returncode)

if __name__ == "__main__":
    main()
