"""Issue coturn REST credentials for a local development APK, never server secrets."""
import argparse
import base64
import hashlib
import hmac
import os
from pathlib import Path
import re
import time

parser = argparse.ArgumentParser()
parser.add_argument('--host', required=True)
parser.add_argument('--secret-file', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--hours', type=int, default=24)
args = parser.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9.-]+', args.host) or not 1 <= args.hours <= 24:
    parser.error('Use a DNS hostname and a validity between 1 and 24 hours')
secret = args.secret_file.read_bytes().strip()
if len(secret) < 32:
    parser.error('Server secret must contain at least 32 bytes')
expiry = int(time.time()) + args.hours * 3600
username = f'{expiry}:torx-dev-{os.urandom(8).hex()}'
credential = base64.b64encode(hmac.new(secret, username.encode(), hashlib.sha1).digest()).decode()
properties = (
    f'TORX_STUN_URLS=stun:{args.host}:3478\n'
    f'TORX_TURN_URLS=turn:{args.host}:3478?transport=udp,turn:{args.host}:3478?transport=tcp,turns:{args.host}:5349?transport=tcp\n'
    f'TORX_TURN_USERNAME={username}\n'
    f'TORX_TURN_CREDENTIAL={credential}\n'
)
descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
    output.write(properties)
print(f'Wrote development credentials, expires at Unix time {expiry}. Server secret was not exported.')
