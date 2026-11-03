"""Host checks for encrypted provisioning. No Spotify account required."""
import base64
import importlib.util
import json
import unittest
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.exceptions import InvalidTag

path = Path(__file__).resolve().parents[1] / 'tools/configure.py'
spec = importlib.util.spec_from_file_location('configure', path)
configure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(configure)

class PairingTest(unittest.TestCase):
    def setUp(self):
        self.private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        public = self.private.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        self.payload = {'access_token': 'test-only-access', 'refresh_token': 'test-only-refresh', 'client_id': 'a' * 32}
        self.envelope = json.loads(configure.envelope(self.payload, base64.b64encode(public).decode()))
        self.key = self.private.decrypt(base64.b64decode(self.envelope['key']), padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))

    def test_roundtrip(self):
        plain = AESGCM(self.key).decrypt(base64.b64decode(self.envelope['iv']), base64.b64decode(self.envelope['data']), None)
        self.assertEqual(json.loads(plain), self.payload)
        self.assertNotIn('test-only-access', json.dumps(self.envelope))

    def test_tampering_rejected(self):
        data = bytearray(base64.b64decode(self.envelope['data']))
        data[0] ^= 1
        with self.assertRaises(InvalidTag):
            AESGCM(self.key).decrypt(base64.b64decode(self.envelope['iv']), bytes(data), None)

    def test_session_randomness(self):
        public = self.private.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        other = json.loads(configure.envelope(self.payload, base64.b64encode(public).decode()))
        self.assertNotEqual(self.envelope['iv'], other['iv'])
        self.assertNotEqual(self.envelope['key'], other['key'])

if __name__ == '__main__':
    unittest.main()
