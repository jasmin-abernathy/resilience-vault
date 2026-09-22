"""Primitive/AAD test only: NOT Tink serialization or streaming interoperability."""
import unittest
from pathlib import Path
import sys
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.exceptions import InvalidTag

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from contracts import header


class AeadTests(unittest.TestCase):
    def test_aes256_gcm_zero_vector(self):
        # Public known-answer fixture; all-zero keys/nonces NEVER used in the app.
        # AES-256-GCM: key=0^256, IV=0^96, plaintext=0^128, AAD empty.
        expected = bytes.fromhex(
            'cea7403d4d606b6e074ec5d3baf39d18' 'd0d1c8a799996bf0265b98b5d48ab919')
        aes = AESGCM(bytes(32))
        self.assertEqual(aes.encrypt(bytes(12), bytes(16), b''), expected)
        self.assertEqual(aes.decrypt(bytes(12), expected, b''), bytes(16))

    def test_every_aad_byte_is_authenticated(self):
        aad = header(2, bytes(range(16)), bytes(range(16, 32)), 1, 1, 7)
        aes = AESGCM(bytes(range(32)))
        nonce = bytes(range(12))
        sealed = aes.encrypt(nonce, b'fixture', aad)
        for index in range(64):
            changed = bytearray(aad)
            changed[index] ^= 1
            with self.subTest(index=index), self.assertRaises(InvalidTag):
                aes.decrypt(nonce, sealed, bytes(changed))

    def test_wrong_key_tamper_truncation_and_suffix(self):
        aes = AESGCM(bytes(range(32)))
        nonce = bytes(range(12))
        sealed = aes.encrypt(nonce, b'fixture', b'context')
        with self.assertRaises(InvalidTag):
            AESGCM(bytes(32)).decrypt(nonce, sealed, b'context')
        for changed in (sealed[:-1], sealed + b'\x00', bytes([sealed[0] ^ 1]) + sealed[1:]):
            with self.assertRaises(InvalidTag):
                aes.decrypt(nonce, changed, b'context')


if __name__ == '__main__':
    unittest.main()
