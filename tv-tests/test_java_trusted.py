"""Ensure desktop ECDSA signatures are interoperable with Java host verifier."""
import base64
from pathlib import Path
import subprocess
import tempfile
import unittest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


class TestJavaTrusted(unittest.TestCase):
    def test_ecdsa_signature_interop(self):
        source = Path(__file__).with_name('VerifyTrusted.java')
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(['javac', '-d', d, str(source)], check=True)
            priv=ec.generate_private_key(ec.SECP256R1())
            der=priv.public_key().public_bytes(serialization.Encoding.DER,
                                                serialization.PublicFormat.SubjectPublicKeyInfo)
            challenge='ARDVPN-LOGIN-V1|'+'AA'*32+'|'+'BB'*32+'|'+'CC'*32
            signed=priv.sign(challenge.encode('ascii'), ec.ECDSA(hashes.SHA256()))
            encoded=lambda value:base64.urlsafe_b64encode(value).decode().rstrip('=')
            result=subprocess.run(['java', '-cp', d, 'VerifyTrusted', encoded(der),
                                   challenge, encoded(signed)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('JAVA_ECDSA_VERIFIED',result.stdout)
            changed=subprocess.run(['java', '-cp', d, 'VerifyTrusted', encoded(der),
                                    challenge+'CORRUPTED', encoded(signed)], capture_output=True)
            self.assertEqual(changed.returncode, 2)
