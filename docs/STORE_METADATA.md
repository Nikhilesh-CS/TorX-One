# Candidate store metadata — draft, not published

Name: TorX One

Short description:
Encrypted messaging with Tor transport and experimental nearby connections.

## Proposed description

TorX One is a pre-release Android messaging app. It keeps your identity,
contacts, messages and session state on your device, with an encrypted local
database. Supported peers can exchange encrypted messages over Tor. Experimental
nearby connections require compatible devices, permissions and Google Play
services. Nearby direct communication and multi-hop mesh are different features.

Voice and video use WebRTC, with connectivity depending on networks and configured
STUN/TURN services. Call media does not travel through Tor. Calls can reveal
network addresses to peers or service operators. Hardware transports require
compatible external hardware and remain experimental.

This version has not completed an independent security audit or the full device
beta campaign. It is not a Signal-compatible client. Uninstalling normally
removes local app data and identity; disabling Android backup does not create a
recovery backup. Copies held by recipients or exported files are outside local
deletion control.

## Submission requirements still requiring maintainer action

- Publish a reachable privacy policy and private security/support contact.
- Complete the actual store Data safety questionnaire against the signed build,
  configured endpoints, third-party SDK behavior, and distribution telemetry.
  Do not infer a blanket "no data shared" answer from encryption or local storage.
- Review microphone, camera, nearby/location, notification and foreground-service
  declarations against the exact release manifest and store forms.
- Obtain actual candidate screenshots; omit personal messages and identities.
- Confirm name, category, content rating, geographic availability and developer
  details in the store account. These fields have not been submitted here.
- Verify increasing versionCode and signing continuity before any upload.

Claims to exclude: independently audited, guaranteed anonymity, permanent bug
freedom, universal offline mesh, guaranteed cross-network calls, recoverable
identity after uninstall, and verified 1.0 readiness. See [privacy](PRIVACY.md),
[threat model](THREAT_MODEL.md) and [release gates](RELEASE.md).
