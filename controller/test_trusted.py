"""ECDSA pairing protocol integration tests against a TLS mock Android host.

These tests validate desktop on-wire behavior, not Android implementation.
"""
import hashlib
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import threading
import unittest
from cryptography.hazmat.primitives.serialization import load_der_public_key
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes
import base64
from protocol import send_line, read_line
from trusted import (pair_controller, trusted_probe, trusted_key, trusted_session,
                     identity_id, load_or_create_identity)


class MockHost:
    def __init__(self, directory):
        d = Path(directory)
        key, cert = d / 'tls.key', d / 'tls.crt'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-subj', '/CN=mock-host', '-keyout', str(key), '-out', str(cert)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest().upper()
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.load_cert_chain(certfile=str(cert), keyfile=str(key))
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen(10)
        self.listener.settimeout(.2)
        self.port = self.listener.getsockname()[1]
        self.running = True
        self.lock = threading.Lock()
        self.trusted = {}
        self.pair_code = 'ABCDEFGHJKLM'
        self.ticket = None
        self.pending_public = None
        self.approve_pairing = True
        self.pause = False
        self.control = True
        self.bad_signatures = 0
        self.received_unauthenticated_lines = []
        self.worker = threading.Thread(target=self.serve, daemon=True)
        self.worker.start()

    def stop(self):
        self.running = False
        self.listener.close()
        self.worker.join(timeout=2)

    def serve(self):
        while self.running:
            try: raw, _ = self.listener.accept()
            except (socket.timeout, OSError): continue
            try:
                with self.context.wrap_socket(raw, server_side=True) as conn:
                    conn.settimeout(2)
                    self.handle(conn)
            except (ssl.SSLError, ConnectionError, OSError, TimeoutError):
                continue

    def handle(self, conn):
        send_line(conn, 'HELLO 1')
        cmd = read_line(conn)
        with self.lock:
            if cmd.startswith('PAIR '):
                _, code, name, pub = cmd.split(' ')
                if code != self.pair_code: send_line(conn,'ERR PAIR_DENIED'); return
                self.pending_public = base64.urlsafe_b64decode(pub + '=' * (-len(pub) % 4))
                self.ticket = 'AA' * 16
                send_line(conn, 'PENDING ' + self.ticket)
                if self.approve_pairing:
                    key_id = hashlib.sha256(self.pending_public).hexdigest().upper()
                    self.trusted[key_id] = self.pending_public
                return
            if cmd.startswith('PAIR_STATUS '):
                if cmd[12:] != self.ticket: send_line(conn,'ERR PAIR_UNKNOWN'); return
                key_id = hashlib.sha256(self.pending_public).hexdigest().upper()
                send_line(conn, 'PAIRED ' + key_id if key_id in self.trusted else 'PENDING')
                return
            if not cmd.startswith('LOGIN '):
                self.received_unauthenticated_lines.append(cmd)
                send_line(conn, 'ERR AUTH_REQUIRED')
                return
            key_id = cmd[6:]
            if key_id not in self.trusted or self.pause:
                send_line(conn, 'ERR NOT_TRUSTED')
                return
            pub = load_der_public_key(self.trusted[key_id])
        nonce = 'A9' * 32
        send_line(conn, 'CHALLENGE ' + nonce)
        line = read_line(conn)
        if not line.startswith('SIGN '): return
        signature = base64.urlsafe_b64decode(line[5:] + '=' * (-len(line[5:]) % 4))
        try:
            pub.verify(signature, ('ARDVPN-LOGIN-V1|' + self.pin + '|' + key_id + '|' + nonce).encode(),
                       ec.ECDSA(hashes.SHA256()))
        except Exception:
            self.bad_signatures += 1
            send_line(conn, 'ERR BAD_SIGNATURE')
            return
        send_line(conn, 'OK 1')
        while True:
            try: command = read_line(conn)
            except (ConnectionError, OSError): break
            with self.lock:
                if key_id not in self.trusted or self.pause:
                    send_line(conn, 'ERR REVOKED'); break
                if command.startswith('KEY '):
                    send_line(conn, 'OK ' + command if self.control else 'ERR CONTROL_NOT_ALLOWED')
                elif command == 'PING':
                    send_line(conn, 'PONG 1')
                elif command == 'INFO':
                    send_line(conn, 'INFO {"device":"Mock TV","android":"16","vpnAddress":"127.0.0.1","remoteControl":true,"version":"0.4.0"}')
                elif command == 'QUIT':
                    send_line(conn, 'BYE'); break
                else:
                    send_line(conn, 'ERR UNKNOWN')


class TrustedTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.keyfile = Path(self.temp.name) / 'client-private.pem'
        self.server = MockHost(self.temp.name)
        self.addCleanup(self.server.stop)

    def pair(self):
        return pair_controller('127.0.0.1', self.server.port, self.server.pin,
                               self.server.pair_code, 'Komputer-test', self.keyfile, 10)

    def test_pair_persists_and_authenticates(self):
        key_id = self.pair()
        self.assertEqual(key_id, identity_id(load_or_create_identity(self.keyfile)))
        self.assertEqual(trusted_key('127.0.0.1', self.server.port, self.server.pin,
                                     'RIGHT', self.keyfile), 'OK KEY RIGHT')
        info = trusted_probe('127.0.0.1', self.server.port, self.server.pin, self.keyfile)
        self.assertEqual(info.device, 'Mock TV')
        self.assertTrue(info.control_enabled)

    def test_pair_rejected_if_no_code(self):
        with self.assertRaises(PermissionError):
            pair_controller('127.0.0.1', self.server.port, self.server.pin,
                            'HGFEDCBA7654', 'Attacker', self.keyfile, 10)
        self.assertFalse(self.server.trusted)

    def test_wrong_fingerprint_sends_no_commands(self):
        with self.assertRaises(ssl.SSLError):
            pair_controller('127.0.0.1', self.server.port, 'FF'*32,
                            self.server.pair_code, 'Attacker', self.keyfile, 10)
        self.assertFalse(self.server.trusted)
        self.assertEqual(self.server.received_unauthenticated_lines, [])

    def test_revocation_denies_existing_connection(self):
        key_id=self.pair()
        with trusted_session('127.0.0.1', self.server.port, self.server.pin, self.keyfile) as sock:
            with self.server.lock:
                self.server.trusted.pop(key_id)
            send_line(sock, 'KEY HOME')
            self.assertEqual(read_line(sock), 'ERR REVOKED')
        with self.assertRaises(PermissionError):
            trusted_key('127.0.0.1', self.server.port, self.server.pin, 'HOME', self.keyfile)

    def test_paused_and_pilot_disabled(self):
        self.pair()
        self.server.control=False
        with self.assertRaisesRegex(PermissionError,'CONTROL_NOT_ALLOWED'):
            trusted_key('127.0.0.1', self.server.port, self.server.pin, 'OK', self.keyfile)
        self.server.pause=True
        with self.assertRaisesRegex(PermissionError,'NOT_TRUSTED'):
            trusted_key('127.0.0.1', self.server.port, self.server.pin, 'OK', self.keyfile)

    def test_identity_file_does_not_change(self):
        one = identity_id(load_or_create_identity(self.keyfile))
        two = identity_id(load_or_create_identity(self.keyfile))
        self.assertEqual(one, two)
        self.assertGreater(self.keyfile.stat().st_size, 100)


if __name__ == '__main__':
    unittest.main()
