# Phase 6 — Security Storage Architecture V2

## Status

Complete. Production secret persistence is routed through one `SecurityRepository` backed by Android Keystore.

## Authority and startup order

`TorXOneApplication` creates the security repository first. The same instance protects:

- Ed25519 and X25519 identity private keys
- pending-invite ephemeral private keys
- relationship root secrets
- queue send and receive authentication keys
- Double Ratchet sessions and skipped message keys
- the SQLCipher database passphrase

Production constructors no longer create or default to `NoOpKeyProtector`. Tests must opt into any test protector explicitly.

## Crypto storage formats

| Version | Meaning | Read behavior | Migration |
| --- | --- | --- | --- |
| V1 | Legacy raw bytes inside the older storage container | Accepted only by an explicitly versioned legacy path | Reprotected as V3 after a successful read |
| V2 | Android Keystore AES-GCM envelope | Authenticated and decrypted with the legacy Keystore protector | Reprotected as V3 after a successful read |
| V3 | Context-bound envelope | Verifies magic, version, clear purpose, and the authenticated purpose inside the ciphertext | Current format |

V3 prevents substituting one kind of secret for another. For example, an identity signing key cannot be opened as a database passphrase or pending-invite key.

Unknown versions, malformed envelopes, purpose mismatches, decryption failures, and failed durable writes fail closed.

## Hardware-backed protection

On Android 9 and later, key generation requests StrongBox. If the device reports that StrongBox is unavailable, generation retries with the normal Android Keystore provider. Existing aliases continue to be used, so an upgrade does not rotate or orphan existing data.

## Migration behavior

- Identity private keys and the database passphrase record their storage format in encrypted preferences and migrate during the first successful load.
- Pending invites migrate legacy V1/V2 private keys when read.
- Relationship and connection material migrates during startup/database restoration.
- Session and skipped-key material migrates when loaded and all new writes use V3.

Migration happens only after legacy material is authenticated or validated. The plaintext is returned to the caller only in memory and the persisted replacement is a V3 envelope.

## Verification

On 2026-09-29:

- `:app:compileDebugKotlin` passed.
- `:app:compileDebugUnitTestKotlin` passed.
- `:app:testDebugUnitTest` passed: 245 tests, 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug` passed and produced `android/app/build/outputs/apk/debug/app-debug.apk`.
- Production source contains no `NoOpKeyProtector()` construction or no-op default.

The JVM tests cover V3 round trips, purpose mismatch rejection, header tampering rejection, and V2-to-V3 migration. Hardware-backed and StrongBox behavior still requires validation on physical Android devices because JVM tests cannot attest the device Keystore implementation.
