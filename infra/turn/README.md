# TorX One TURN relay

Prepared deployment files. No relay has been deployed or verified yet.

## VPS prerequisites

Use a Linux VPS with a static public IPv4 address, Docker Compose, and a DNS name such as `turn.your-domain.example`. Point its DNS A record directly to the VPS, without an HTTP/CDN proxy. Obtain a trusted TLS certificate for this name and arrange automatic renewal. TURN media is outside Tor; the relay sees client IPs and call traffic metadata.

Allow TCP and UDP 3478, TCP 5349, and UDP 49160–49260 in both the cloud firewall and host firewall. Keep SSH access available. Relay ports must preserve their port numbers if the VPS uses NAT.

## Configure and start

1. Copy this directory onto the VPS.
2. Copy `turnserver.conf.example` to `turnserver.conf`. Replace the domain and public IPv4 placeholders. For 1:1 NAT, configure the public/private address mapping.
3. Generate a random server secret on the VPS with `openssl rand -hex 32`. Store it privately and set `static-auth-secret` to the same value. Keep the configuration readable only by the server administrator and container process.
4. Put the certificate and key in `tls/fullchain.pem` and `tls/privkey.pem`. Renew them automatically and restart coturn after renewal.
5. Set `COTURN_IMAGE` in a local `.env` file to a reviewed official `coturn/coturn` image version, preferably pinned by digest. Run `docker compose config`, then `docker compose up -d`. Check `docker compose logs --tail=100`.

## Connect a development APK

The app already accepts `TORX_STUN_URLS`, `TORX_TURN_URLS`, `TORX_TURN_USERNAME`, and `TORX_TURN_CREDENTIAL` as Gradle properties or environment variables.

Run `issue-dev-credentials.py` on the VPS using Python 3, with `--host YOUR_DOMAIN --secret-file PRIVATE_SECRET_FILE --output turn-client.properties`. Transfer only the generated credentials securely to the build computer. Apply these properties to a local build; keep them out of Git. They expire within 24 hours, so this is development validation only.

For production, implement an authenticated HTTPS credential endpoint issuing short-lived coturn REST credentials and have the app fetch them before each call. That endpoint is not implemented here. Do not bundle the server secret or permanent shared credentials in the APK.

## Required live verification

Confirm authentication succeeds, wrong/expired credentials fail, and WebRTC selects a relay candidate. Place a bidirectional audio call between different networks, then switch Wi-Fi/cellular and inspect reconnection, audio, timeout, and cleanup. A successful build or ringing alone does not verify relay media.

References: [coturn configuration](https://github.com/coturn/coturn/blob/master/examples/etc/turnserver.conf), [official container](https://github.com/coturn/coturn/blob/master/docker/coturn/README.md), [WebRTC TURN overview](https://webrtc.org/getting-started/turn-server).
