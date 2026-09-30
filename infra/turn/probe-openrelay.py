"""Test documented Open Relay static-auth TURN allocations; prints no credentials.

The shared test secret is intentionally public on Metered's Open Relay page.
This probe does not deploy a server or establish two-peer media connectivity.
"""
import base64
import hashlib
import hmac
import os
import socket
import ssl
import struct
import time

HOST = 'staticauth.openrelay.metered.ca'
COOKIE = 0x2112A442
SECRET = b'openrelayprojectsecret'


def attr(kind, value):
    return struct.pack('!HH', kind, len(value)) + value + bytes((-len(value)) % 4)


def packet(kind, txid, attributes, key=None):
    body = b''.join(attributes)
    header = struct.pack('!HHI', kind, len(body) + (24 if key else 0), COOKIE) + txid
    if key:
        body += attr(0x0008, hmac.new(key, header + body, hashlib.sha1).digest())
    return header + body


def exact(stream, count):
    result = b''
    while len(result) < count:
        chunk = stream.recv(count - len(result))
        if not chunk:
            raise OSError('TURN connection closed')
        result += chunk
    return result


def exchange(stream, mode, request, txid):
    stream.sendall(request)
    if mode == 'udp':
        response = stream.recv(65535)
    else:
        response = exact(stream, 20)
        response += exact(stream, struct.unpack('!H', response[2:4])[0])
    kind, length, cookie = struct.unpack('!HHI', response[:8])
    if cookie != COOKIE or response[8:20] != txid or len(response) != 20 + length:
        raise OSError('Invalid TURN response')
    attrs = {}
    offset = 20
    while offset < len(response):
        tag, size = struct.unpack('!HH', response[offset:offset + 4])
        offset += 4
        attrs[tag] = response[offset:offset + size]
        offset += size + (-size) % 4
    return kind, attrs


def probe(mode, port):
    stream = socket.create_connection((HOST, port), timeout=8) if mode != 'udp' else socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    stream.settimeout(8)
    if mode == 'udp':
        stream.connect((HOST, port))
    if mode == 'tls':
        stream = ssl.create_default_context().wrap_socket(stream, server_hostname=HOST)
    with stream:
        txid = os.urandom(12)
        requested = attr(0x0019, b'\x11\0\0\0')
        kind, challenge = exchange(stream, mode, packet(0x0003, txid, [requested]), txid)
        if kind != 0x0113 or 0x0014 not in challenge or 0x0015 not in challenge:
            raise OSError('TURN did not provide authentication challenge')
        realm, nonce = challenge[0x0014], challenge[0x0015]
        username = str(int(time.time()) + 3600).encode() + b':torx-probe'
        password = base64.b64encode(hmac.new(SECRET, username, hashlib.sha1).digest())
        key = hashlib.md5(username + b':' + realm + b':' + password).digest()
        txid = os.urandom(12)
        auth = [requested, attr(0x0006, username), attr(0x0014, realm), attr(0x0015, nonce)]
        kind, result = exchange(stream, mode, packet(0x0003, txid, auth, key), txid)
        if kind != 0x0103 or 0x0016 not in result:
            raise OSError('Authenticated TURN allocation rejected')
        print(f'{mode.upper()}:{port} authenticated allocation PASSED')
        txid = os.urandom(12)
        refresh = [attr(0x000D, struct.pack('!I', 0)), *auth[1:]]
        exchange(stream, mode, packet(0x0004, txid, refresh, key), txid)


if __name__ == '__main__':
    successes = 0
    for mode, port in [('udp', 80), ('tcp', 443), ('tls', 443)]:
        try:
            probe(mode, port)
            successes += 1
        except (OSError, ValueError, KeyError, struct.error) as error:
            print(f'{mode.upper()}:{port} allocation FAILED ({type(error).__name__})')
    raise SystemExit(0 if successes == 3 else 1)
