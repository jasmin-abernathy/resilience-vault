from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import sqlite3
import time
from typing import Any, Callable, Mapping

from .auth import (
    AuthorizationError,
    DeleteCapabilityAuthenticator,
    DenyAllDeleteCapabilityAuthenticator,
    DenyAllProvisioningAuthenticator,
    ProvisioningAuthenticator,
)
from .digest import ProvisioningDigestInput, request_digest_hex
from .store import (
    CapabilityUnavailable,
    DeleteStatus,
    ProvisioningRow,
    ReferenceStore,
    StoreConflict,
    StoreNotFound,
    Tombstoned,
)

MAX_BODY_BYTES = 8 * 1024
MAX_RESPONSE_BYTES = 8 * 1024
_JSON_CONTENT_TYPE = "application/json"
_OPERATION_RE = re.compile(r"^/v1/delete-provisionings/([0-9a-f]{64})$")
_REVOKE_RE = re.compile(r"^/v1/delete-provisionings/([0-9a-f]{64})/revoke$")
_DELETE_RE = re.compile(r"^/v1/vault-generations/([0-9a-f]{64})/([0-9a-f]{64})$")


class RequestError(RuntimeError):
    def __init__(self, status: int, code: str):
        super().__init__(code)
        self.status = status
        self.code = code


class ReferenceApi:
    def __init__(
        self,
        *,
        store: ReferenceStore,
        service_id: str,
        provisioning_authenticator: ProvisioningAuthenticator | None = None,
        delete_authenticator: DeleteCapabilityAuthenticator | None = None,
        now: Callable[[], int] | None = None,
    ) -> None:
        if not service_id:
            raise ValueError("reference service_id must be explicit")
        self.store = store
        self.service_id = service_id
        self.provisioning_authenticator = provisioning_authenticator or DenyAllProvisioningAuthenticator()
        self.delete_authenticator = delete_authenticator or DenyAllDeleteCapabilityAuthenticator()
        self.now = now or (lambda: int(time.time()))

    def create_server(self, *, host: str = "127.0.0.1", port: int = 0) -> ThreadingHTTPServer:
        if host != "127.0.0.1":
            raise ValueError("reference server may bind only to IPv4 loopback")
        api = self

        class Handler(BaseHTTPRequestHandler):
            server_version = "ResilienceVaultReference/1"
            sys_version = ""

            def log_message(self, format: str, *args: Any) -> None:  # noqa: A003 - BaseHTTPRequestHandler contract
                return

            def do_PUT(self) -> None:
                api._dispatch(self, "PUT")

            def do_GET(self) -> None:
                api._dispatch(self, "GET")

            def do_POST(self) -> None:
                api._dispatch(self, "POST")

            def do_DELETE(self) -> None:
                api._dispatch(self, "DELETE")

            def do_PATCH(self) -> None:
                api._dispatch(self, "PATCH")

        return ThreadingHTTPServer((host, port), Handler)

    def _dispatch(self, handler: BaseHTTPRequestHandler, method: str) -> None:
        try:
            if method == "PUT":
                match = _OPERATION_RE.fullmatch(handler.path)
                if match:
                    self._put_provisioning(handler, match.group(1))
                    return
            elif method == "GET":
                match = _OPERATION_RE.fullmatch(handler.path)
                if match:
                    self._get_provisioning(handler, match.group(1))
                    return
            elif method == "POST":
                match = _REVOKE_RE.fullmatch(handler.path)
                if match:
                    self._revoke_provisioning(handler, match.group(1))
                    return
            elif method == "DELETE":
                match = _DELETE_RE.fullmatch(handler.path)
                if match:
                    self._delete_generation(handler, match.group(1), match.group(2))
                    return
            raise RequestError(405, "METHOD_OR_ROUTE_NOT_ALLOWED")
        except RequestError as exc:
            self._json(handler, exc.status, {"version": 1, "error": exc.code})
        except AuthorizationError:
            self._json(handler, 403, {"version": 1, "error": "FORBIDDEN"})
        except StoreConflict:
            self._json(handler, 409, {"version": 1, "error": "CONFLICT"})
        except (StoreNotFound,):
            self._json(handler, 404, {"version": 1, "error": "NOT_FOUND"})
        except (Tombstoned, CapabilityUnavailable):
            self._json(handler, 409, {"version": 1, "error": "GENERATION_UNAVAILABLE"})
        except sqlite3.DatabaseError:
            self._json(handler, 503, {"version": 1, "error": "STORAGE_UNAVAILABLE"})
        except (ValueError, TypeError, AttributeError, UnicodeDecodeError, json.JSONDecodeError):
            self._json(handler, 400, {"version": 1, "error": "INVALID_REQUEST"})

    def _put_provisioning(self, handler: BaseHTTPRequestHandler, operation_id: str) -> None:
        body = self._read_json_exact(
            handler,
            {
                "version",
                "serviceId",
                "tenantId",
                "vaultId",
                "generation",
                "operationId",
                "requestDigest",
                "verifierVersion",
                "verifierHex",
            },
        )
        if type(body["version"]) is not int:
            raise RequestError(400, "INVALID_REQUEST")
        for key in (
            "serviceId", "tenantId", "vaultId", "generation", "operationId",
            "requestDigest", "verifierVersion", "verifierHex",
        ):
            if not isinstance(body[key], str):
                raise RequestError(400, "INVALID_REQUEST")
        if body["version"] != 1 or body["operationId"] != operation_id:
            raise RequestError(400, "BINDING_MISMATCH")
        if body["serviceId"] != self.service_id:
            raise RequestError(400, "SERVICE_MISMATCH")

        principal = self.provisioning_authenticator.authenticate(
            _headers(handler),
            right="provision",
            service_id=self.service_id,
            now_epoch_seconds=self.now(),
        )
        principal.require(right="provision", service_id=self.service_id, now_epoch_seconds=self.now())
        if principal.tenant_id != body["tenantId"]:
            raise AuthorizationError("tenant mismatch")

        digest_input = ProvisioningDigestInput(
            service_id=body["serviceId"],
            tenant_id=body["tenantId"],
            vault_id=body["vaultId"],
            generation=body["generation"],
            operation_id=body["operationId"],
            verifier_version=body["verifierVersion"],
            verifier_hex=body["verifierHex"],
            version=body["version"],
        )
        expected_digest = request_digest_hex(digest_input)
        if body["requestDigest"] != expected_digest:
            raise RequestError(409, "REQUEST_DIGEST_MISMATCH")

        row = ProvisioningRow(
            tenant_id=body["tenantId"],
            service_id=body["serviceId"],
            operation_id=body["operationId"],
            vault_id=body["vaultId"],
            generation=body["generation"],
            request_digest=body["requestDigest"],
            verifier_version=body["verifierVersion"],
            verifier_hex=body["verifierHex"],
            state="PROVISIONED",
        )
        stored, created = self.store.put_provisioning(row)
        self._json(handler, 201 if created else 200, _provisioning_response(stored))

    def _get_provisioning(self, handler: BaseHTTPRequestHandler, operation_id: str) -> None:
        self._require_empty_body(handler)
        principal = self.provisioning_authenticator.authenticate(
            _headers(handler),
            right="status",
            service_id=self.service_id,
            now_epoch_seconds=self.now(),
        )
        principal.require(right="status", service_id=self.service_id, now_epoch_seconds=self.now())
        row = self.store.get_provisioning(tenant_id=principal.tenant_id, operation_id=operation_id)
        if row is None:
            self._json(
                handler,
                404,
                {"version": 1, "operationId": operation_id, "state": "UNKNOWN"},
            )
            return
        self._json(handler, 200, _provisioning_response(row))

    def _revoke_provisioning(self, handler: BaseHTTPRequestHandler, operation_id: str) -> None:
        self._require_empty_body(handler)
        principal = self.provisioning_authenticator.authenticate(
            _headers(handler),
            right="revoke",
            service_id=self.service_id,
            now_epoch_seconds=self.now(),
        )
        principal.require(right="revoke", service_id=self.service_id, now_epoch_seconds=self.now())
        row = self.store.revoke_provisioning(tenant_id=principal.tenant_id, operation_id=operation_id)
        self._json(handler, 200, _provisioning_response(row))

    def _delete_generation(self, handler: BaseHTTPRequestHandler, vault_id: str, generation: str) -> None:
        self._require_empty_body(handler)
        principal = self.delete_authenticator.authenticate(_headers(handler), service_id=self.service_id)
        if (
            principal.service_id != self.service_id
            or principal.vault_id != vault_id
            or principal.generation != generation
        ):
            raise AuthorizationError("DELETE capability scope mismatch")
        status = self.store.delete_generation(
            tenant_id=principal.tenant_id,
            service_id=principal.service_id,
            vault_id=principal.vault_id,
            generation=principal.generation,
        )
        self._json(handler, 202 if status.state == "PENDING" else 200, _delete_response(status))

    def _read_json_exact(self, handler: BaseHTTPRequestHandler, expected_keys: set[str]) -> dict[str, Any]:
        if handler.headers.get("Content-Type") != _JSON_CONTENT_TYPE:
            raise RequestError(415, "UNSUPPORTED_CONTENT_TYPE")
        raw = self._read_body(handler, require_nonempty=True)

        def pairs_hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
            out: dict[str, Any] = {}
            for key, value in pairs:
                if key in out:
                    raise ValueError("duplicate JSON key")
                out[key] = value
            return out

        value = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs_hook)
        if not isinstance(value, dict) or set(value) != expected_keys:
            raise RequestError(400, "JSON_SHAPE_MISMATCH")
        return value

    def _require_empty_body(self, handler: BaseHTTPRequestHandler) -> None:
        raw_length = handler.headers.get("Content-Length")
        if raw_length is None:
            return
        try:
            length = int(raw_length)
        except ValueError as exc:
            raise RequestError(400, "INVALID_CONTENT_LENGTH") from exc
        if length != 0:
            raise RequestError(400, "BODY_NOT_ALLOWED")

    def _read_body(self, handler: BaseHTTPRequestHandler, *, require_nonempty: bool) -> bytes:
        raw_length = handler.headers.get("Content-Length")
        if raw_length is None:
            raise RequestError(411, "CONTENT_LENGTH_REQUIRED")
        try:
            length = int(raw_length)
        except ValueError as exc:
            raise RequestError(400, "INVALID_CONTENT_LENGTH") from exc
        if length < 0 or length > MAX_BODY_BYTES or (require_nonempty and length == 0):
            raise RequestError(413 if length > MAX_BODY_BYTES else 400, "BODY_SIZE_INVALID")
        body = handler.rfile.read(length)
        if len(body) != length:
            raise RequestError(400, "TRUNCATED_BODY")
        return body

    def _json(self, handler: BaseHTTPRequestHandler, status: int, value: Mapping[str, Any]) -> None:
        body = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")
        if len(body) > MAX_RESPONSE_BYTES:
            raise RuntimeError("reference response exceeds configured maximum")
        handler.send_response(status)
        handler.send_header("Content-Type", _JSON_CONTENT_TYPE)
        handler.send_header("Content-Length", str(len(body)))
        handler.send_header("Cache-Control", "no-store")
        handler.end_headers()
        handler.wfile.write(body)


def _headers(handler: BaseHTTPRequestHandler) -> Mapping[str, str]:
    return {key: value for key, value in handler.headers.items()}


def _provisioning_response(row: ProvisioningRow) -> dict[str, Any]:
    return {
        "version": 1,
        "serviceId": row.service_id,
        "tenantId": row.tenant_id,
        "vaultId": row.vault_id,
        "generation": row.generation,
        "operationId": row.operation_id,
        "requestDigest": row.request_digest,
        "state": row.state,
    }


def _delete_response(status: DeleteStatus) -> dict[str, Any]:
    return {
        "version": 1,
        "serviceId": status.service_id,
        "tenantId": status.tenant_id,
        "vaultId": status.vault_id,
        "generation": status.generation,
        "deleteOperationId": status.delete_operation_id,
        "state": status.state,
    }
