# Security model

Identity primitives use Bouncy Castle Ed25519 signatures and X25519 agreement. Session code uses a custom Double Ratchet implementation, HKDF/HMAC SHA-256 and authenticated encryption. The project does not use the audited Signal client protocol stack; forward secrecy and recovery properties depend on the implementation and correct durable state transitions.

Secure envelope recipient binding and authenticated session identity must agree. Queue authenticators and opaque transport framing do not replace end-to-end authentication. Sequence/replay limits are enforced by receiving/session components; envelope decoding alone does not establish authorization.

Room data is backed by SQLCipher. Production key wrapping uses Android Keystore AES-GCM, with StrongBox availability varying by device. Inspect configured fallback behavior before asserting hardware guarantees. App lock is a UI access control and is separate from requiring user authentication for each key operation. Do not market a screen lock as authenticated key protection.

Android backup is disabled in the manifest. Uninstall normally removes local application data and identity; reinstall is not an identity recovery mechanism. Deleting a conversation preserves the peer relationship for reopening.

Known limits: custom ratchet has not received independent review; database encryption does not protect an unlocked application process; transport fallback changes observable metadata; WebRTC media is outside Tor; bundled TURN credentials are extractable from an APK. Group fan-out/control convergence and experimental hardware paths require additional runtime validation.
