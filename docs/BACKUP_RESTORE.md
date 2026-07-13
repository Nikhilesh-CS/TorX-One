# Identity Backup and Restore

Identity backup lets a user preserve their TorX One identity before reinstalling the app or changing devices.

## What the backup is for

The backup preserves the identity data needed to restore the same account identity.

This matters because contacts trust the cryptographic identity, not just the display name.

## Exporting a backup

1. Open Settings.
2. Select Export Identity Backup.
3. Enter a strong password.
4. Save the backup file somewhere safe.

New backup files use the extension:

```text
.torxone-backup
```

## Restoring a backup

1. Open Restore Identity Backup.
2. Select the backup file.
3. Enter the original backup password.
4. Wait for the app to restore identity data.
5. Verify Tor status and onion address after restore.

## Password rules

The backup password is required for restore.

TorX One cannot recover a lost backup password. If the password is lost, the backup is effectively unusable.

## Safety notes

- Store the backup file privately.
- Do not share the backup file.
- Do not reuse weak passwords.
- Test restore only with a backup you can afford to validate.
- Keep older backups until a new backup has been confirmed.

## Troubleshooting

### Incorrect password or corrupted backup file

This means the backup could not be decrypted or parsed.

Common causes:

- Wrong password
- Damaged backup file
- Incomplete file transfer
- Backup from an incompatible version

### Tor failed to initialize with restored keys

This means the identity data was restored far enough for Tor initialization to begin, but the hidden-service identity could not start.

Common causes:

- Damaged Tor hidden-service key files
- Permission problem in restored files
- Tor runtime startup failure
- Incompatible backup format from an older version

