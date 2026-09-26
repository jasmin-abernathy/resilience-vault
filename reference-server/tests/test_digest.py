from __future__ import annotations

import unittest

from reference_server.digest import ProvisioningDigestInput, canonical_request_bytes, request_digest_hex


class DigestSpecTest(unittest.TestCase):
    def test_known_vector_is_stable(self) -> None:
        value = ProvisioningDigestInput(
            service_id="test-primary",
            tenant_id="tenant-1",
            vault_id="1" * 64,
            generation="2" * 64,
            operation_id="3" * 64,
            verifier_version="opaque-v1",
            verifier_hex="4" * 64,
        )
        self.assertEqual(
            "f22cbfa8212e00b9643eb70d0e58315129b8556bc31e2c1d06e9fb68fd197641",
            request_digest_hex(value),
        )
        self.assertTrue(canonical_request_bytes(value).startswith(bytes.fromhex("0021") + b"RV-DELETE-PROVISIONING-REQUEST-V1"))

    def test_digest_changes_when_verifier_changes(self) -> None:
        a = ProvisioningDigestInput("test-primary", "tenant-1", "1"*64, "2"*64, "3"*64, "opaque-v1", "4"*64)
        b = ProvisioningDigestInput("test-primary", "tenant-1", "1"*64, "2"*64, "3"*64, "opaque-v1", "5"*64)
        self.assertNotEqual(request_digest_hex(a), request_digest_hex(b))

    def test_noncanonical_input_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            request_digest_hex(ProvisioningDigestInput("TEST", "tenant-1", "1"*64, "2"*64, "3"*64, "opaque-v1", "4"*64))


if __name__ == "__main__":
    unittest.main()
