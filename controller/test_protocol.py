"""Local protocol integration test, requires openssl. No Android hardware required."""
from __future__ import annotations
import hashlib
import os
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import threading
import unittest
from protocol import probe, clean_hex


class ProtocolTests(unittest.TestCase):
    def test_clean_hex(self):
        self.assertEqual(clean_hex("ab:" * 15 + "ab", 32), "AB" * 16)
        with self.assertRaises(ValueError):
            clean_hex("secret", 32)

    def test_tls_handshake(self):
        with tempfile.TemporaryDirectory() as d:
            keyfile, certfile = Path(d) / "server.key", Path(d) / "server.crt"
            subprocess.run([
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-subj", "/CN=LocalTest", "-keyout", str(keyfile), "-out", str(certfile)
            ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            der = ssl.PEM_cert_to_DER_cert(certfile.read_text())
            fingerprint = hashlib.sha256(der).hexdigest()
            token = "AB" * 16
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(certfile=str(certfile), keyfile=str(keyfile))
            listener = socket.socket()
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            def respond():
                try:
                    client, _ = listener.accept()
                    with ctx.wrap_socket(client, server_side=True) as conn:
                        stream = conn.makefile("rwb")
                        stream.write(b"HELLO 1\n"); stream.flush()
                        if stream.readline().decode().strip() != "AUTH " + token: return
                        stream.write(b"OK 1\n"); stream.flush()
                        if stream.readline() != b"PING\n": return
                        stream.write(b"PONG 0\n"); stream.flush()
                        if stream.readline() != b"INFO\n": return
                        stream.write(b'INFO {"device":"Mock Android","android":"16","vpnAddress":"127.0.0.1","version":"0.1","remoteControl":false}\n'); stream.flush()
                        if stream.readline() == b"QUIT\n":
                            stream.write(b"BYE\n"); stream.flush()
                finally:
                    listener.close()
            thread = threading.Thread(target=respond)
            thread.start()
            try:
                result = probe("127.0.0.1", port, token, fingerprint)
                self.assertEqual(result.device, "Mock Android")
                self.assertEqual(result.address, "127.0.0.1")
                self.assertFalse(result.control_enabled)
            finally:
                thread.join(timeout=8)


if __name__ == "__main__":
    unittest.main()
