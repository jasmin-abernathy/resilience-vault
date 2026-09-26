from __future__ import annotations

import json
import sqlite3
import threading
import unittest
import urllib.error
import urllib.request

from fakes import StaticTestProvisioningAuthenticator
from reference_server.auth import ProvisioningPrincipal
from reference_server.http_api import ReferenceApi
from reference_server.proof import (
    DeleteCompletionProofCandidate,
    MAX_PROOF_BODY_BYTES,
    ProofValidationError,
)


class ProofBodyBoundaryTest(unittest.TestCase):
    def _valid_body(self) -> bytes:
        value = {
            "schemaVersion": 1,
            "serviceId": "test-primary",
            "tenantId": "tenant-1",
            "vaultId": "1" * 64,
            "generation": "2" * 64,
            "deleteOperationId": "3" * 32,
            "requestDigest": "4" * 64,
            "state": "COMPLETE",
            "tombstoneRevision": 9,
            "responseId": "5" * 32,
            "verifiedAtEpochSeconds": 1_700_000_100,
            "purgeScope": ["active", "staging"],
            "backupPolicyId": "policy-v1",
        }
        return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")

    def test_parser_accepts_bounded_bytes_and_rejects_other_buffer_types(self) -> None:
        raw = self._valid_body()
        self.assertLessEqual(len(raw), MAX_PROOF_BODY_BYTES)
        self.assertEqual("COMPLETE", DeleteCompletionProofCandidate.parse_json(raw).state)

        with self.assertRaisesRegex(ProofValidationError, "must be bytes"):
            DeleteCompletionProofCandidate.parse_json(bytearray(raw))  # type: ignore[arg-type]

    def test_valid_json_with_large_trailing_padding_is_rejected_before_decode(self) -> None:
        raw = self._valid_body()
        padded = raw + b" " * (MAX_PROOF_BODY_BYTES - len(raw) + 1)
        self.assertEqual(MAX_PROOF_BODY_BYTES + 1, len(padded))
        with self.assertRaisesRegex(ProofValidationError, "exceeds maximum size"):
            DeleteCompletionProofCandidate.parse_json(padded)


class _FailingStatusStore:
    def get_provisioning(self, *, tenant_id: str, operation_id: str):
        raise sqlite3.DatabaseError("sensitive ledger detail")


class SQLiteFailureHttpTest(unittest.TestCase):
    def test_sqlite_failure_returns_generic_503_without_reopening_state(self) -> None:
        principal = ProvisioningPrincipal(
            tenant_id="tenant-1",
            rights=frozenset({"status"}),
            expires_at_epoch_seconds=2_000_000_000,
            audience="test-primary",
        )
        api = ReferenceApi(
            store=_FailingStatusStore(),  # type: ignore[arg-type]
            service_id="test-primary",
            provisioning_authenticator=StaticTestProvisioningAuthenticator(
                {"Bearer status-test": principal}
            ),
            now=lambda: 1_700_000_000,
        )
        server = api.create_server()
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        host, port = server.server_address
        request = urllib.request.Request(
            f"http://{host}:{port}/v1/delete-provisionings/{'3' * 64}",
            headers={"Authorization": "Bearer status-test"},
            method="GET",
        )
        try:
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(request, timeout=3)
            response = raised.exception
            self.assertEqual(503, response.code)
            body = response.read().decode("ascii")
            self.assertEqual(
                {"error": "STORAGE_UNAVAILABLE", "version": 1},
                json.loads(body),
            )
            self.assertNotIn("sensitive ledger detail", body)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
