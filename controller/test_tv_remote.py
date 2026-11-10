"""v0.3 integration tests for a TV remote command over pinned TLS."""
import hashlib
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import threading
import unittest
from protocol import send_tv_key


class RemoteTvTests(unittest.TestCase):
    def with_server(self, response, handler):
        with tempfile.TemporaryDirectory() as d:
            keyfile, certfile = Path(d) / "server.key", Path(d) / "server.crt"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                            "-days", "1", "-subj", "/CN=LocalTvTest", "-keyout", str(keyfile),
                            "-out", str(certfile)], check=True, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL)
            fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(certfile.read_text())).hexdigest()
            token = "BC" * 16
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(certfile=str(certfile), keyfile=str(keyfile))
            listener = socket.socket()
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            captured = []
            def serve():
                try:
                    raw, _ = listener.accept()
                    with ctx.wrap_socket(raw, server_side=True) as conn:
                        conn.settimeout(3)
                        stream = conn.makefile("rwb")
                        stream.write(b"HELLO 1\n"); stream.flush()
                        while True:
                            line = stream.readline()
                            if not line: break
                            value = line.decode().strip()
                            captured.append(value)
                            if value == "AUTH " + token:
                                stream.write(b"OK 1\n")
                            elif value == "KEY RIGHT":
                                stream.write((response + "\n").encode())
                            elif value == "QUIT":
                                stream.write(b"BYE\n")
                            else:
                                stream.write(b"ERR UNSUPPORTED_COMMAND\n")
                            stream.flush()
                except (ssl.SSLError, ConnectionError, TimeoutError):
                    pass
                finally:
                    listener.close()
            t = threading.Thread(target=serve)
            t.start()
            try:
                handler(port, token, fingerprint)
            finally:
                t.join(timeout=8)
            return captured

    def test_successful_key(self):
        def check(port, key, fingerprint):
            self.assertEqual(send_tv_key("127.0.0.1", port, key, fingerprint, "RIGHT"), "OK KEY RIGHT")
        lines = self.with_server("OK KEY RIGHT", check)
        self.assertEqual(lines, ["AUTH " + "BC" * 16, "KEY RIGHT", "QUIT"])

    def test_denied_key(self):
        def check(port, key, fingerprint):
            with self.assertRaisesRegex(PermissionError, "CONTROL_NOT_ARMED"):
                send_tv_key("127.0.0.1", port, key, fingerprint, "RIGHT")
        lines = self.with_server("ERR CONTROL_NOT_ARMED", check)
        self.assertEqual(lines[-1], "KEY RIGHT")

    def test_wrong_certificate_does_not_authenticate(self):
        def check(port, key, fingerprint):
            with self.assertRaises(ssl.SSLError):
                send_tv_key("127.0.0.1", port, key, "FF" * 32, "RIGHT")
        lines = self.with_server("OK KEY RIGHT", check)
        self.assertEqual(lines, [])

    def test_forbidden_command_rejected_early(self):
        with self.assertRaises(ValueError):
            send_tv_key("127.0.0.1", 48376, "AB" * 16, "BC" * 32, "INSTALL_APK")


if __name__ == "__main__":
    unittest.main()
