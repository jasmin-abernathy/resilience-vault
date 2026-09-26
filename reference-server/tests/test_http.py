from __future__ import annotations

from contextlib import redirect_stderr
from io import StringIO
import json
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from reference_server.auth import DeleteCapabilityPrincipal, ProvisioningPrincipal
from reference_server.digest import ProvisioningDigestInput, request_digest_hex
from reference_server.http_api import ReferenceApi
from reference_server.store import ReferenceStore
from fakes import StaticTestDeleteAuthenticator, StaticTestProvisioningAuthenticator


class HttpIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.store = ReferenceStore(
            root / "main.sqlite",
            root / "ledger.sqlite",
            now=lambda: 1_700_000_000,
            delete_operation_id_factory=lambda: "d" * 32,
        )
        self.service = "test-primary"
        self.tenant = "tenant-1"
        self.vault = "1" * 64
        self.generation = "2" * 64
        self.operation = "3" * 64
        self.verifier = "4" * 64
        self.store.create_generation(
            tenant_id=self.tenant,
            service_id=self.service,
            vault_id=self.vault,
            generation=self.generation,
        )
        principal = ProvisioningPrincipal(
            tenant_id=self.tenant,
            rights=frozenset({"provision", "status", "revoke"}),
            expires_at_epoch_seconds=2_000_000_000,
            audience=self.service,
        )
        delete = DeleteCapabilityPrincipal(
            tenant_id=self.tenant,
            service_id=self.service,
            vault_id=self.vault,
            generation=self.generation,
        )
        provision_only = ProvisioningPrincipal(
            tenant_id=self.tenant, rights=frozenset({"provision"}),
            expires_at_epoch_seconds=2_000_000_000, audience=self.service,
        )
        status_only = ProvisioningPrincipal(
            tenant_id=self.tenant, rights=frozenset({"status"}),
            expires_at_epoch_seconds=2_000_000_000, audience=self.service,
        )
        revoke_only = ProvisioningPrincipal(
            tenant_id=self.tenant, rights=frozenset({"revoke"}),
            expires_at_epoch_seconds=2_000_000_000, audience=self.service,
        )
        self.api = ReferenceApi(
            store=self.store,
            service_id=self.service,
            provisioning_authenticator=StaticTestProvisioningAuthenticator({
                "Bearer provision-test": principal,
                "Bearer provision-only": provision_only,
                "Bearer status-only": status_only,
                "Bearer revoke-only": revoke_only,
            }),
            delete_authenticator=StaticTestDeleteAuthenticator({"Delete delete-test": delete}),
            now=lambda: 1_700_000_000,
        )
        self.server = self.api.create_server()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.server.server_address
        self.base = f"http://{host}:{port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.tmp.cleanup()

    def _body(self, *, verifier: str | None = None) -> dict[str, object]:
        verifier = verifier or self.verifier
        digest_input = ProvisioningDigestInput(
            service_id=self.service,
            tenant_id=self.tenant,
            vault_id=self.vault,
            generation=self.generation,
            operation_id=self.operation,
            verifier_version="opaque-v1",
            verifier_hex=verifier,
        )
        return {
            "version": 1,
            "serviceId": self.service,
            "tenantId": self.tenant,
            "vaultId": self.vault,
            "generation": self.generation,
            "operationId": self.operation,
            "requestDigest": request_digest_hex(digest_input),
            "verifierVersion": "opaque-v1",
            "verifierHex": verifier,
        }

    def _request(self, method: str, path: str, *, body=None, auth: str | None = None, raw_body: bytes | None = None, content_type="application/json"):
        headers = {}
        if auth is not None:
            headers["Authorization"] = auth
        data = raw_body
        if body is not None:
            data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        if data is not None:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                payload = json.loads(response.read().decode("ascii"))
                return response.status, response.headers, payload
        except urllib.error.HTTPError as exc:
            payload = json.loads(exc.read().decode("ascii"))
            return exc.code, exc.headers, payload

    def _provision(self):
        return self._request(
            "PUT",
            f"/v1/delete-provisionings/{self.operation}",
            body=self._body(),
            auth="Bearer provision-test",
        )

    def test_put_replay_get_and_revoke_are_bound_and_idempotent(self) -> None:
        status, headers, body = self._provision()
        self.assertEqual(201, status)
        self.assertEqual("application/json", headers["Content-Type"])
        self.assertEqual("PROVISIONED", body["state"])
        self.assertEqual(self.tenant, body["tenantId"])
        self.assertNotIn("verifierHex", body)

        status, _, replay = self._provision()
        self.assertEqual(200, status)
        self.assertEqual(body, replay)

        status, _, fetched = self._request(
            "GET",
            f"/v1/delete-provisionings/{self.operation}",
            auth="Bearer provision-test",
        )
        self.assertEqual(200, status)
        self.assertEqual(body, fetched)

        status, _, revoked = self._request(
            "POST",
            f"/v1/delete-provisionings/{self.operation}/revoke",
            auth="Bearer provision-test",
        )
        self.assertEqual(200, status)
        self.assertEqual("REVOKED", revoked["state"])
        status, _, revoked_again = self._request(
            "POST",
            f"/v1/delete-provisionings/{self.operation}/revoke",
            auth="Bearer provision-test",
        )
        self.assertEqual(200, status)
        self.assertEqual(revoked, revoked_again)

    def test_contradictory_put_is_409_and_does_not_replace_committed_row(self) -> None:
        self.assertEqual(201, self._provision()[0])
        status, _, body = self._request(
            "PUT",
            f"/v1/delete-provisionings/{self.operation}",
            body=self._body(verifier="5" * 64),
            auth="Bearer provision-test",
        )
        self.assertEqual(409, status)
        self.assertEqual("CONFLICT", body["error"])
        status, _, fetched = self._request(
            "GET",
            f"/v1/delete-provisionings/{self.operation}",
            auth="Bearer provision-test",
        )
        self.assertEqual(200, status)
        self.assertEqual(self._body()["requestDigest"], fetched["requestDigest"])

    def test_get_after_unknown_operation_is_explicit_unknown_not_absence_proof(self) -> None:
        unknown = "9" * 64
        status, _, body = self._request(
            "GET",
            f"/v1/delete-provisionings/{unknown}",
            auth="Bearer provision-test",
        )
        self.assertEqual(404, status)
        self.assertEqual({"version": 1, "operationId": unknown, "state": "UNKNOWN"}, body)

    def test_delete_tombstone_then_complete_uses_attested_body_not_204(self) -> None:
        self.assertEqual(201, self._provision()[0])
        status, _, pending = self._request(
            "DELETE",
            f"/v1/vault-generations/{self.vault}/{self.generation}",
            auth="Delete delete-test",
        )
        self.assertEqual(202, status)
        self.assertEqual("PENDING", pending["state"])
        self.assertEqual("d" * 32, pending["deleteOperationId"])
        self.assertEqual(self.vault, pending["vaultId"])
        self.assertEqual(self.generation, pending["generation"])

        self.store.run_purge_once()
        status, _, complete = self._request(
            "DELETE",
            f"/v1/vault-generations/{self.vault}/{self.generation}",
            auth="Delete delete-test",
        )
        self.assertEqual(200, status)
        self.assertEqual("COMPLETE", complete["state"])
        self.assertEqual(pending["deleteOperationId"], complete["deleteOperationId"])

    def test_delete_capability_has_no_provision_status_or_revoke_rights(self) -> None:
        routes = [
            ("PUT", f"/v1/delete-provisionings/{self.operation}", self._body()),
            ("GET", f"/v1/delete-provisionings/{self.operation}", None),
            ("POST", f"/v1/delete-provisionings/{self.operation}/revoke", None),
        ]
        for method, path, body in routes:
            with self.subTest(method=method, path=path):
                status, _, response = self._request(method, path, body=body, auth="Delete delete-test")
                self.assertEqual(403, status)
                self.assertEqual("FORBIDDEN", response["error"])

        status, _, response = self._request(
            "DELETE",
            f"/v1/vault-generations/{self.vault}/{self.generation}",
            auth="Bearer provision-test",
        )
        self.assertEqual(403, status)
        self.assertEqual("FORBIDDEN", response["error"])

    def test_authorization_matrix_enforces_least_privilege_per_route(self) -> None:
        put_path = f"/v1/delete-provisionings/{self.operation}"
        get_path = put_path
        revoke_path = put_path + "/revoke"
        delete_path = f"/v1/vault-generations/{self.vault}/{self.generation}"

        matrix = [
            ("Bearer provision-only", "PUT", put_path, self._body(), 201),
            ("Bearer provision-only", "GET", get_path, None, 403),
            ("Bearer provision-only", "POST", revoke_path, None, 403),
            ("Bearer provision-only", "DELETE", delete_path, None, 403),
            ("Bearer status-only", "PUT", put_path, self._body(), 403),
            ("Bearer status-only", "GET", get_path, None, 200),
            ("Bearer status-only", "POST", revoke_path, None, 403),
            ("Bearer status-only", "DELETE", delete_path, None, 403),
            ("Bearer revoke-only", "PUT", put_path, self._body(), 403),
            ("Bearer revoke-only", "GET", get_path, None, 403),
            ("Bearer revoke-only", "POST", revoke_path, None, 200),
            ("Bearer revoke-only", "DELETE", delete_path, None, 403),
        ]
        # The first PUT creates the operation used by status/revoke rows later in the matrix.
        for auth, method, path, body, expected in matrix:
            with self.subTest(auth=auth, method=method):
                status, _, _ = self._request(method, path, body=body, auth=auth)
                self.assertEqual(expected, status)

    def test_get_after_simulated_lost_put_response_recovers_committed_state(self) -> None:
        body = self._body()
        # Commit directly, representing a server commit followed by transport/process loss before reply.
        from reference_server.store import ProvisioningRow
        self.store.put_provisioning(ProvisioningRow(
            tenant_id=self.tenant, service_id=self.service, operation_id=self.operation,
            vault_id=self.vault, generation=self.generation, request_digest=body["requestDigest"],
            verifier_version="opaque-v1", verifier_hex=self.verifier, state="PROVISIONED",
        ))
        status, _, response = self._request(
            "GET", f"/v1/delete-provisionings/{self.operation}", auth="Bearer status-only"
        )
        self.assertEqual(200, status)
        self.assertEqual("PROVISIONED", response["state"])
        self.assertEqual(body["requestDigest"], response["requestDigest"])

    def test_expired_or_wrong_audience_principal_is_rejected_by_api_even_if_authenticator_returns_it(self) -> None:
        expired = ProvisioningPrincipal(
            tenant_id=self.tenant, rights=frozenset({"provision"}),
            expires_at_epoch_seconds=1_600_000_000, audience=self.service,
        )
        wrong_audience = ProvisioningPrincipal(
            tenant_id=self.tenant, rights=frozenset({"provision"}),
            expires_at_epoch_seconds=2_000_000_000, audience="other-service",
        )
        for token, principal in (("Bearer expired", expired), ("Bearer wrong-audience", wrong_audience)):
            api = ReferenceApi(
                store=self.store, service_id=self.service,
                provisioning_authenticator=StaticTestProvisioningAuthenticator({token: principal}),
                now=lambda: 1_700_000_000,
            )
            server = api.create_server()
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            old_base = self.base
            host, port = server.server_address
            self.base = f"http://{host}:{port}"
            try:
                status, _, response = self._request(
                    "PUT", f"/v1/delete-provisionings/{self.operation}", body=self._body(), auth=token
                )
                self.assertEqual(403, status)
                self.assertEqual("FORBIDDEN", response["error"])
            finally:
                self.base = old_base
                server.shutdown(); server.server_close(); thread.join(timeout=2)

    def test_repeated_delete_before_worker_returns_same_pending_operation(self) -> None:
        self.assertEqual(201, self._provision()[0])
        path = f"/v1/vault-generations/{self.vault}/{self.generation}"
        first_status, _, first = self._request("DELETE", path, auth="Delete delete-test")
        second_status, _, second = self._request("DELETE", path, auth="Delete delete-test")
        self.assertEqual(202, first_status)
        self.assertEqual(202, second_status)
        self.assertEqual("PENDING", second["state"])
        self.assertEqual(first["deleteOperationId"], second["deleteOperationId"])

    def test_default_authenticators_refuse_everything(self) -> None:
        api = ReferenceApi(store=self.store, service_id=self.service)
        server = api.create_server()
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        host, port = server.server_address
        base = self.base
        self.base = f"http://{host}:{port}"
        try:
            status, _, body = self._request(
                "PUT",
                f"/v1/delete-provisionings/{self.operation}",
                body=self._body(),
                auth="Bearer anything",
            )
            self.assertEqual(403, status)
            self.assertEqual("FORBIDDEN", body["error"])
        finally:
            self.base = base
            server.shutdown(); server.server_close(); thread.join(timeout=2)

    def test_server_refuses_public_bind(self) -> None:
        with self.assertRaises(ValueError):
            self.api.create_server(host="0.0.0.0")

    def test_json_parser_rejects_duplicate_unknown_keys_and_wrong_content_type(self) -> None:
        body = self._body()
        raw = (
            '{"version":1,"version":1,"serviceId":"%s","tenantId":"%s",'
            '"vaultId":"%s","generation":"%s","operationId":"%s",'
            '"requestDigest":"%s","verifierVersion":"opaque-v1","verifierHex":"%s"}'
        ) % (
            self.service, self.tenant, self.vault, self.generation, self.operation,
            body["requestDigest"], self.verifier,
        )
        status, _, result = self._request(
            "PUT",
            f"/v1/delete-provisionings/{self.operation}",
            raw_body=raw.encode(),
            auth="Bearer provision-test",
        )
        self.assertEqual(400, status)
        self.assertEqual("INVALID_REQUEST", result["error"])

        unknown = dict(body)
        unknown["callbackUrl"] = "https://evil.example/"
        status, _, result = self._request(
            "PUT",
            f"/v1/delete-provisionings/{self.operation}",
            body=unknown,
            auth="Bearer provision-test",
        )
        self.assertEqual(400, status)
        self.assertEqual("JSON_SHAPE_MISMATCH", result["error"])

        status, _, result = self._request(
            "PUT",
            f"/v1/delete-provisionings/{self.operation}",
            body=body,
            auth="Bearer provision-test",
            content_type="text/plain",
        )
        self.assertEqual(415, status)
        self.assertEqual("UNSUPPORTED_CONTENT_TYPE", result["error"])

    def test_wrong_json_types_and_oversized_body_fail_closed(self) -> None:
        body = self._body()
        body["serviceId"] = 123
        status, _, response = self._request(
            "PUT", f"/v1/delete-provisionings/{self.operation}", body=body, auth="Bearer provision-test"
        )
        self.assertEqual(400, status)
        self.assertEqual("INVALID_REQUEST", response["error"])

        oversized = b"{" + b" " * (8 * 1024) + b"}"
        status, _, response = self._request(
            "PUT", f"/v1/delete-provisionings/{self.operation}", raw_body=oversized, auth="Bearer provision-test"
        )
        self.assertEqual(413, status)
        self.assertEqual("BODY_SIZE_INVALID", response["error"])

    def test_tenant_from_body_cannot_override_authenticated_tenant(self) -> None:
        body = self._body()
        body["tenantId"] = "tenant-2"
        body["requestDigest"] = request_digest_hex(
            ProvisioningDigestInput(
                self.service,
                "tenant-2",
                self.vault,
                self.generation,
                self.operation,
                "opaque-v1",
                self.verifier,
            )
        )
        status, _, response = self._request(
            "PUT",
            f"/v1/delete-provisionings/{self.operation}",
            body=body,
            auth="Bearer provision-test",
        )
        self.assertEqual(403, status)
        self.assertEqual("FORBIDDEN", response["error"])

    def test_no_secret_is_written_by_http_logging(self) -> None:
        secret = "Delete very-secret-test-capability"
        captured = StringIO()
        with redirect_stderr(captured):
            status, _, _ = self._request(
                "DELETE",
                f"/v1/vault-generations/{self.vault}/{self.generation}",
                auth=secret,
            )
        self.assertEqual(403, status)
        self.assertNotIn(secret, captured.getvalue())
        self.assertEqual("", captured.getvalue())


if __name__ == "__main__":
    unittest.main()
