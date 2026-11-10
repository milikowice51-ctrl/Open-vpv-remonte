"""Local TLS interoperability tests for the pure-Java Android TV protocol client."""
from __future__ import annotations
import hashlib
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "android-host/tv-controller/src/main/java/pl/remotevpn/tv/TlsProbeClient.java"
HARNESS = Path(__file__).with_name("ProbeHarness.java")


@unittest.skipUnless(shutil.which("javac") and shutil.which("java") and shutil.which("openssl"),
                     "Requires javac, java and openssl")
class JavaTlsProbeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.folder = Path(cls.temp.name)
        subprocess.run(["javac", "-d", str(cls.folder), str(JAVA), str(HARNESS)], check=True)
        cls.cert = cls.folder / "server.crt"
        cls.key = cls.folder / "server.key"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=Android-Remote-Test", "-keyout", str(cls.key),
                        "-out", str(cls.cert)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        der = ssl.PEM_cert_to_DER_cert(cls.cert.read_text())
        cls.fp = hashlib.sha256(der).hexdigest().upper()

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_case(self, supplied_fp, expected_auth):
        token = "AB" * 16
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(certfile=str(self.cert), keyfile=str(self.key))
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = listener.getsockname()[1]
        received = []

        def serve():
            try:
                with listener:
                    raw, _ = listener.accept()
                    with context.wrap_socket(raw, server_side=True) as conn:
                        conn.settimeout(2)
                        conn.sendall(b"HELLO 1\n")
                        stream = conn.makefile("rwb", buffering=0)
                        try:
                            auth = stream.readline()
                            received.append(auth)
                            if auth != (b"AUTH " + token.encode("ascii") + b"\n"):
                                return
                            conn.sendall(b"OK 1\n")
                            self.assertEqual(stream.readline(), b"PING\n")
                            conn.sendall(b"PONG 123\n")
                            self.assertEqual(stream.readline(), b"INFO\n")
                            conn.sendall(b'INFO {"device":"Mock Android","version":"0.1.0"}\n')
                            self.assertEqual(stream.readline(), b"QUIT\n")
                            conn.sendall(b"BYE\n")
                        except (ConnectionError, TimeoutError, OSError):
                            pass
            except (OSError, ssl.SSLError):
                pass

        thread = threading.Thread(target=serve, daemon=True)
        thread.start()
        try:
            result = subprocess.run(["java", "-cp", str(self.folder), "ProbeHarness", "127.0.0.1",
                                     str(port), token, supplied_fp], capture_output=True, text=True, timeout=12)
        finally:
            thread.join(timeout=5)
        if expected_auth:
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('Mock Android', result.stdout)
            self.assertEqual(received, [b"AUTH " + token.encode("ascii") + b"\n"])
        else:
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Odcisk SHA-256", result.stderr)
            self.assertNotIn(b"AUTH ", received)

    def test_good_fingerprint_and_session(self):
        self.run_case(self.fp, True)

    def test_bad_fingerprint_sends_no_auth(self):
        bad = "A" * 64 if self.fp != "A" * 64 else "B" * 64
        self.run_case(bad, False)


if __name__ == "__main__":
    unittest.main()
