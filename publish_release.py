import subprocess
import os
import sys

NOTES = """## What's New in TorX One v1.0.28

### 🐛 Critical Bug Fixes & Resilience Overhaul

#### 1. 🧅 Tor Call Signaling Port Mismatch Resolved (`CallTransportSession.kt`)
- Corrected Tor call signaling socket port from `8080` to `8765`, aligning with `TorManager.LOCAL_PORT` and `HiddenServicePort 8765 127.0.0.1:8765`.
- Fixes instant connection refused/timeouts when initiating or receiving WebRTC audio/video calls over Tor hidden services.

#### 2. 🛡️ WebRTC Native JNI & ProGuard Obfuscation Rules (`proguard-rules.pro`)
- Added comprehensive keep rules for `org.webrtc.**`, `com.torxone.app.call.**`, `com.torxone.app.network.**`, `com.torxone.app.security.**`, and `com.torxone.app.crypto.**`.
- Prevents R8/ProGuard from stripping WebRTC JNI native callbacks, observers, and reflection-based models in release builds.
- Fixes the issue where calls would connect and ring, but immediately drop upon being answered.

#### 3. 🔄 Multi-Transport Routing & Tor Fallback Priority (`MessageRouter.kt`)
- **Smart Transport Selection (`getBestTransport`)**: Prioritizes direct `TOR` over blind `NEARBY_RELAY` when the contact is not currently connected via Nearby, preventing remote calls from being wrongly routed to nearby devices.
- **Reliable Session & Chat Delivery Fallback**: Automatically falls back to Tor if a direct Nearby connection fails or if the recipient is out of Nearby range, ensuring messages never silently disappear into dead relays and get stuck at single-tick.
- **Bi-Directional Delivery Receipts (ACKs & Read Receipts)**: Enforces that messages delivered via Tor always route their delivery acknowledgments and read receipts back over Tor rather than incorrectly attempting Nearby broadcast.

#### 4. 🔑 Sender Identity & Reverse Routing in Double Ratchet (`SessionManager.kt`)
- Included `senderOnion` within the encrypted `SessionWirePayload`.
- Enables the receiving peer to immediately bind and verify the sender's onion address for accurate contact synchronization and bidirectional session routing.

---

### 📦 Checksums & Artifacts
- **File**: `TorX-One-v1.0.28-release.apk`
- **Size**: 43.70 MB (43,698,878 bytes)
- **SHA-256**: `CC0AD1CE894FB9E6BECA5F8431F2BC73E75E99C06133A535C12D6325117DDA8E`
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
    
    notes_file = "release_notes_v1.0.28.md"
    with open(notes_file, "w", encoding="utf-8") as f:
        f.write(NOTES)
        
    apk_path = "TorX-One-v1.0.28-release.apk"
    if not os.path.exists(apk_path):
        print(f"Error: {apk_path} not found")
        sys.exit(1)
        
    cmd = [
        "gh", "release", "create", "v1.0.28",
        apk_path,
        "--title", "TorX One v1.0.28 - End-to-End Delivery & Tor Call Signaling Fixes",
        "--notes-file", notes_file
    ]
    
    print("Running:", " ".join(cmd))
    res = subprocess.run(cmd, env=env, capture_output=True, text=True)
    print("STDOUT:", res.stdout)
    print("STDERR:", res.stderr)
    
    if res.returncode == 0:
        print("Release v1.0.28 created successfully!")
    else:
        print("Failed to create release, returncode:", res.returncode)
        sys.exit(res.returncode)

if __name__ == "__main__":
    main()
