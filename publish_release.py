import subprocess
import os
import sys

NOTES = """## What's New in TorX One v1.0.26

### 🎨 Brand Identity & App Logo
- **Modern TorX One Glyph**: Replaced legacy branding with the new blue gradient 'R/X' mesh glyph logo (`ic_launcher.jpg`).
- **System-Wide Consistency**: Updated application manifest launcher, round icon, and background notification shade surfaces.

### 🌙 Dark Mode Setting & Dynamic Theming
- **Dedicated Dark Mode Toggle**: Added a clearly visible switch in Settings with dynamic subtitles (*"Dark theme enabled"* / *"Switch between light and dark theme"*).
- **Theme-Adaptive Palette**: Full dynamic switching between TorX Professional Light and Slate Dark palettes across surfaces, cards, borders, typography, and status/navigation bar insets.
- **Default Light Theme**: Light mode enabled by default with instant toggle capability.

### ⚡ Dedicated Battery & Performance Screen
- **Settings Screen Streamlined**: Consolidated 100+ lines of inline metrics into a clean, collapsed card (`⚡ Battery & Performance ›`).
- **Full-Screen Control**: Dedicated screen providing battery saver, balanced, and performance profiles, active background component status (Tor, Bluetooth, Wi-Fi Direct, Mesh), and Android optimization settings.

### 👥 Messages Header & Navigation Cleanup
- **Create Group Action**: Moved group creation to the top-right of the Messages header as a dedicated `👥` (`Icons.Rounded.Groups`) action, eliminating the ambiguous bottom-right floating `+` button.
- **Universal Dual QR Scanner**: Screen header top-right QR icon directly opens full-screen CameraX scanner with corner viewfinder brackets, handling both TorX contact keys and group invites.

### 📦 Checksums & Artifacts
- **File**: `TorX-One-v1.0.26-release.apk`
- **Size**: ~47.3 MB
- **SHA-256**: `24D1476092788B2F88B067576874064D3920E27045867D8F94A31A965FF24564`
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
    
    notes_file = "release_notes_v1.0.26.md"
    with open(notes_file, "w", encoding="utf-8") as f:
        f.write(NOTES)
        
    apk_path = "TorX-One-v1.0.26-release.apk"
    if not os.path.exists(apk_path):
        print(f"Error: {apk_path} not found")
        sys.exit(1)
        
    cmd = [
        "gh", "release", "create", "v1.0.26",
        apk_path,
        "--title", "TorX One v1.0.26 - New App Logo, Dark Mode Setting & UX Refinement",
        "--notes-file", notes_file
    ]
    
    print("Running:", " ".join(cmd))
    res = subprocess.run(cmd, env=env, capture_output=True, text=True)
    print("STDOUT:", res.stdout)
    print("STDERR:", res.stderr)
    
    if res.returncode == 0:
        print("Release created successfully!")
    else:
        print("Failed to create release, returncode:", res.returncode)
        sys.exit(res.returncode)

if __name__ == "__main__":
    main()
