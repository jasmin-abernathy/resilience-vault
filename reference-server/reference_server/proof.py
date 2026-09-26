from __future__ import annotations

from dataclasses import dataclass
import json
import re
from typing import Any

_LABEL = re.compile(r"^[a-z0-9][a-z0-9._:@-]*$")
_HEX64 = re.compile(r"^[0-9a-f]{64}$")
_HEX_ID = re.compile(r"^[0-9a-f]{32,64}$")
_ALLOWED_SCOPE = frozenset({"active", "staging", "versions", "replicas"})
MAX_PROOF_BODY_BYTES = 8 * 1024


class ProofValidationError(ValueError):
    pass


@dataclass(frozen=True)
class DeleteCompletionProofCandidate:
    schema_version: int
    service_id: str
    tenant_id: str
    vault_id: str
    generation: str
    delete_operation_id: str
    request_digest: str
    state: str
    tombstone_revision: int
    response_id: str
    verified_at_epoch_seconds: int
    purge_scope: tuple[str, ...]
    backup_policy_id: str

    def __post_init__(self) -> None:
        if self.schema_version != 1:
            raise ProofValidationError("unsupported proof schema version")
        for name, value, limit in (
            ("serviceId", self.service_id, 64),
            ("tenantId", self.tenant_id, 128),
            ("backupPolicyId", self.backup_policy_id, 128),
        ):
            if not isinstance(value, str) or not 1 <= len(value) <= limit or not _LABEL.fullmatch(value):
                raise ProofValidationError(f"{name} is not canonical")
        for name, value in (("vaultId", self.vault_id), ("generation", self.generation), ("requestDigest", self.request_digest)):
            if not isinstance(value, str) or not _HEX64.fullmatch(value):
                raise ProofValidationError(f"{name} must be 64 lowercase hex")
        for name, value in (("deleteOperationId", self.delete_operation_id), ("responseId", self.response_id)):
            if not isinstance(value, str) or not _HEX_ID.fullmatch(value):
                raise ProofValidationError(f"{name} is not canonical")
        if self.state not in {"PENDING", "COMPLETE"}:
            raise ProofValidationError("unknown delete proof state")
        if type(self.tombstone_revision) is not int or self.tombstone_revision <= 0:
            raise ProofValidationError("tombstoneRevision must be positive")
        if type(self.verified_at_epoch_seconds) is not int or self.verified_at_epoch_seconds <= 0:
            raise ProofValidationError("verifiedAtEpochSeconds must be positive")
        if not self.purge_scope or tuple(sorted(set(self.purge_scope))) != self.purge_scope:
            raise ProofValidationError("purgeScope must be sorted, unique and non-empty")
        if any(item not in _ALLOWED_SCOPE for item in self.purge_scope):
            raise ProofValidationError("purgeScope contains unknown member")

    @classmethod
    def parse_json(cls, raw: bytes) -> "DeleteCompletionProofCandidate":
        if type(raw) is not bytes:
            raise ProofValidationError("proof body must be bytes")
        if not raw:
            raise ProofValidationError("missing proof body")
        if len(raw) > MAX_PROOF_BODY_BYTES:
            raise ProofValidationError("proof body exceeds maximum size")

        def pairs_hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
            out: dict[str, Any] = {}
            for key, value in pairs:
                if key in out:
                    raise ProofValidationError("duplicate proof field")
                out[key] = value
            return out

        try:
            value = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs_hook)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ProofValidationError("invalid proof JSON") from exc
        if not isinstance(value, dict):
            raise ProofValidationError("proof must be an object")
        expected = {
            "schemaVersion", "serviceId", "tenantId", "vaultId", "generation",
            "deleteOperationId", "requestDigest", "state", "tombstoneRevision",
            "responseId", "verifiedAtEpochSeconds", "purgeScope", "backupPolicyId",
        }
        if set(value) != expected:
            raise ProofValidationError("proof fields mismatch")
        scope = value["purgeScope"]
        if not isinstance(scope, list) or any(not isinstance(x, str) for x in scope):
            raise ProofValidationError("purgeScope must be a string array")
        return cls(
            schema_version=value["schemaVersion"],
            service_id=value["serviceId"],
            tenant_id=value["tenantId"],
            vault_id=value["vaultId"],
            generation=value["generation"],
            delete_operation_id=value["deleteOperationId"],
            request_digest=value["requestDigest"],
            state=value["state"],
            tombstone_revision=value["tombstoneRevision"],
            response_id=value["responseId"],
            verified_at_epoch_seconds=value["verifiedAtEpochSeconds"],
            purge_scope=tuple(scope),
            backup_policy_id=value["backupPolicyId"],
        )


@dataclass(frozen=True)
class DeleteProofExpectation:
    service_id: str
    tenant_id: str
    vault_id: str
    generation: str
    delete_operation_id: str
    request_digest: str
    min_tombstone_revision: int
    required_purge_scope: frozenset[str]
    min_verified_at_epoch_seconds: int


def validate_complete_candidate(proof: DeleteCompletionProofCandidate, expected: DeleteProofExpectation) -> None:
    if proof.state != "COMPLETE":
        raise ProofValidationError("proof is not COMPLETE")
    actual_binding = (
        proof.service_id, proof.tenant_id, proof.vault_id, proof.generation,
        proof.delete_operation_id, proof.request_digest,
    )
    expected_binding = (
        expected.service_id, expected.tenant_id, expected.vault_id, expected.generation,
        expected.delete_operation_id, expected.request_digest,
    )
    if actual_binding != expected_binding:
        raise ProofValidationError("proof binding mismatch")
    if proof.tombstone_revision < expected.min_tombstone_revision:
        raise ProofValidationError("stale tombstone revision")
    if proof.verified_at_epoch_seconds < expected.min_verified_at_epoch_seconds:
        raise ProofValidationError("stale verification time")
    if not expected.required_purge_scope.issubset(set(proof.purge_scope)):
        raise ProofValidationError("purge scope incomplete")


def require_complete_http_candidate(status: int, body: bytes, expected: DeleteProofExpectation) -> DeleteCompletionProofCandidate:
    # This proves only schema/binding. It does NOT authenticate who emitted the bytes.
    if status != 200:
        raise ProofValidationError("COMPLETE requires an explicit 200 proof body")
    proof = DeleteCompletionProofCandidate.parse_json(body)
    validate_complete_candidate(proof, expected)
    return proof
