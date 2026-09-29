# TorX Wi-Fi HaLow gateway

Phase 11 uses Wi-Fi HaLow as a long-range IP link. The initial hardware topology supports phones without native 802.11ah radios:

```text
Android phone ─ ordinary Wi-Fi/USB Ethernet ─ TorX HaLow gateway
              ─────── 802.11ah long-range IP ───────
remote TorX HaLow gateway ─ ordinary Wi-Fi/USB Ethernet ─ Android phone
```

The gateway should run OpenWrt or an equivalent auditable Linux system with a certified 802.11ah module. It advertises `_torx-halow._tcp` using DNS-SD on the phone-facing network and serves the authenticated TorX gateway protocol on TCP port 45821.

Each gateway needs a unique Ed25519 key. The phone sends a fresh challenge during every connection. A gateway becomes an active TorX transport only after its signed capabilities match a previously paired fingerprint.

Gateway responsibilities:

- enforce its regulatory region, channel, bandwidth, and transmit-power limits;
- provide WPA3 on the link where supported;
- keep IP forwarding limited to the TorX overlay unless the owner enables general routing;
- advertise link metrics and maximum payload honestly;
- forward opaque TorX packets without decrypting application content;
- apply queue, peer, and rate limits before accepting traffic;
- publish transmission results and link loss promptly.

The phone protocol is described in [PROTOCOL.md](PROTOCOL.md). Hardware selection and a flashable OpenWrt service remain pending.
