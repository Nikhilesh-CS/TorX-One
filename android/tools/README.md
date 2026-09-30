# Independent Tor transport check

Build requirements: JDK 17, Android SDK platform `platforms;android-37.1`, and the
build tools selected by Gradle. The target SDK remains 36 and minimum SDK remains 26.
Tor Android is resolved from Guardian Project's Maven repository at version 0.4.9.13.

## Two-device gate

Install the current debug APK on both phones using `adb install -r` to retain data.
Unlock the app on each device and wait for Tor readiness. Then run:

```powershell
python android/tools/verify_tor_two_devices.py --adb C:/Android/sdk/platform-tools/adb.exe --phone-a SERIAL_A --phone-b SERIAL_B
```

The check verifies bootstrap 100, SOCKS availability, valid distinct v3 onion
addresses, and a nonce-matching PONG in each direction. It uses the first phone's
SOCKS proxy to reach the other phone's onion endpoint, then reverses direction.
The debug endpoint bypasses contacts, Room, ratchets, messages, UI, and calls.
It is disabled in release builds and does not print onion addresses.

A successful check proves onion connectivity for this run; it does not prove
contact pairing, text persistence, ACK delivery, call ringing, or media transport.

## Implementation after the transport preparation

The user authorized continuing code work without waiting for physical phones.

- Standard calls and files use ICE ALL: LAN, Google STUN, and optional TURN.
  Direct peers can learn network addresses.
- Maximum Call Privacy in Settings applies to new sessions: relay-only ICE,
  no STUN, and rejection of direct candidates. Requires configured working TURN.
  Selected-pair stats omit IP addresses.
- Call UI and notifications distinguish preparation, queued offer, transport
  acceptance, remote ringing, ICE negotiation, and connected media.
- Notification setup requests permission and warns when background ringing or
  lock-screen full-screen presentation is disabled. In-app ringtone playback
  follows the Activity lifecycle and the phone's ringer mode.
- ICE disconnects trigger encrypted restart offer/answer signaling. The original
  caller owns restart initiation to avoid simultaneous renegotiation.
- Files can use a separate silent DATA session with no audio/video tracks, or an
  existing authenticated call's DataChannel. Both phones need this protocol version.
- RTC packets are bounded to 16 KB fragments, with one bounded chunk assembly,
  buffered-send backpressure, receiver acceptance ACKs, and ACK timeout fallback.
  Existing per-chunk AEAD, persisted progress, resume, and final SHA-256 checks apply.
- If the RTC lane is unavailable, encrypted chunks use the existing routed
  transport. Temporary failures stay queued with backoff and startup recovery.
- Chat/control fallback order is Tor, Nearby, Wi-Fi Direct, then other configured
  transports. Standard RTC media can work without TURN; restrictive NATs may
  still require it. ICE selects a working pair, not sequential transport retries.

## Remaining live gates from the repair plan

Complete them in order after both directions above pass:

1. Contact bootstrap, repeated bidirectional text persistence and matching ACKs.
2. CALL_OFFER acceptance and incoming ringing on the receiving phone.
3. Configured, live TURN allocations over UDP, TCP, and TLS, and selected relay-pair proof.
4. Call delivery states and incoming permission/lifecycle checks on real devices.
5. Bidirectional voice on different networks, hangup and connection recovery.
6. Video after voice passes.
7. Large RTCDataChannel file transfer, interrupted/resumed transfer, SHA-256
   integrity, and Tor fallback when the peer is offline or WebRTC is unavailable.

These gates require live devices. Maximum Call Privacy and networks blocking
direct connectivity require a working TURN relay.
The Tor dependency upgrade and a green unit suite do not close those gates.
The public Open Relay probe timed out for UDP/TCP/TLS from this computer; no public
relay was enabled. Supply a working relay via the documented TURN build settings.
