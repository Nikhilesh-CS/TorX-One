# QR Contacts

TorX One supports identity sharing through QR codes.

## Purpose

Typing or pasting long contact keys is inconvenient. QR scanning makes contact exchange faster and less error-prone.

## How it works

1. User A opens their identity QR code.
2. User B opens Add Contact.
3. User B taps the QR scanner beside the contact key field.
4. TorX One scans the QR code.
5. The app validates that the scanned value is a valid contact string.
6. The contact key field is filled automatically.
7. User B confirms the contact.

## Validation

The scanner only accepts QR values that can be parsed as valid TorX One contact data.

Invalid QR codes are ignored instead of being added as contacts.

## Current contact prefix

The current contact URI prefix is:

```text
astra:
```

This is a legacy protocol prefix from the earlier project name. The product name is TorX One. The prefix is kept for compatibility with existing identities and backups until a migration is implemented in code.

## Camera permission

QR scanning requires Android camera permission. The permission is used only for scanning contact QR codes.

