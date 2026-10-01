"""Bidirectional debug onion PING/PONG, without Room or messaging state."""
import argparse
import os
import re
import socket
import struct
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--phone-a', required=True)
parser.add_argument('--phone-b', required=True)
args = parser.parse_args()

def adb(serial, *command, data=None):
    return subprocess.check_output([args.adb, '-s', serial, *command], input=data, text=True, timeout=15).strip()

def info(serial):
    response = adb(serial, 'shell', 'run-as', 'com.torxone.app', 'toybox', 'nc', '-U', '-w', '3', '-q', '3',
                   '/data/user/0/com.torxone.app/app_TorService/data/ControlSocket',
                   data='AUTHENTICATE\r\nGETINFO status/bootstrap-phase net/listeners/socks\r\nQUIT\r\n')
    if 'PROGRESS=100' not in response:
        raise RuntimeError(f'{serial}: Tor has not reached bootstrap 100')
    ports = re.findall(r'127\.0\.0\.1:(\d+)', response)
    if not ports:
        raise RuntimeError(f'{serial}: no IPv4 SOCKS listener')
    onion = adb(serial, 'shell', 'run-as', 'com.torxone.app', 'cat',
                '/data/user/0/com.torxone.app/app_torx_onion_v3/hostname')
    if not re.fullmatch(r'[a-z2-7]{56}\.onion', onion):
        raise RuntimeError(f'{serial}: invalid onion hostname')
    print(f'{serial}: bootstrap=100; SOCKS valid; onion valid')
    return int(ports[0]), onion

def read_exact(sock, count):
    result = bytearray()
    while len(result) < count:
        chunk = sock.recv(count - len(result))
        if not chunk:
            raise RuntimeError('Unexpected EOF')
        result.extend(chunk)
    return bytes(result)

def ping(serial, socks_port, onion):
    forwarded = adb(serial, 'forward', 'tcp:0', f'tcp:{socks_port}')
    try:
        with socket.create_connection(('127.0.0.1', int(forwarded)), timeout=120) as sock:
            sock.sendall(b'\x05\x01\x00')
            if read_exact(sock, 2) != b'\x05\x00':
                raise RuntimeError('SOCKS authentication failed')
            host = onion.encode('ascii')
            sock.sendall(b'\x05\x01\x00\x03' + bytes([len(host)]) + host + struct.pack('!H', 17654))
            header = read_exact(sock, 4)
            if header[0] != 5 or header[1] != 0:
                raise RuntimeError(f'SOCKS connection failed with status {header[1]}')
            size = {1: 4, 4: 16}.get(header[3])
            if header[3] == 3:
                size = read_exact(sock, 1)[0]
            if size is None:
                raise RuntimeError('Invalid SOCKS reply address')
            read_exact(sock, size + 2)
            nonce = os.urandom(16)
            sock.sendall(struct.pack('!I', 0x54585031) + nonce)
            if read_exact(sock, 20) != struct.pack('!I', 0x54585032) + nonce:
                raise RuntimeError('PONG mismatch')
            print(f'{serial}: onion PING/PONG passed')
    finally:
        adb(serial, 'forward', '--remove', f'tcp:{forwarded}')

try:
    port_a, onion_a = info(args.phone_a)
    port_b, onion_b = info(args.phone_b)
    if onion_a == onion_b:
        raise RuntimeError('Both devices have the same onion identity')
    ping(args.phone_a, port_a, onion_b)
    ping(args.phone_b, port_b, onion_a)
except (RuntimeError, OSError, subprocess.SubprocessError) as error:
    # RuntimeError messages above contain only bounded diagnostic state, never
    # onion addresses, payloads or credentials. Other errors may embed commands.
    detail = str(error) if isinstance(error, RuntimeError) else type(error).__name__
    print(f'Tor diagnostic FAILED: {detail}')
    raise SystemExit(1)
