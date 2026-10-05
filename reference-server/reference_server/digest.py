from __future__ import annotations

from dataclasses import dataclass
import hashlib
import re
import struct

DOMAIN = b"RV-DELETE-PROVISIONING-REQUEST-V1"
FORMAT_VERSION = 1
_HEX_64 = re.compile(r"^[0-9a-f]{64}$")
_LABEL = re.compile(r"^[a-z0-9][a-z0-9._:@-]*$")


@dataclass(frozen=True)
class ProvisioningDigestInput:
    service_id: str
    tenant_id: str
    vault_id: str
    generation: str
    operation_id: str
    verifier_version: str
    verifier_hex: str
    version: int = FORMAT_VERSION

    def validate(self) -> None:
        if self.version != FORMAT_VERSION:
            raise ValueError("unsupported provisioning request version")
        _require_label(self.service_id, 64, "serviceId")
        _require_label(self.tenant_id, 128, "tenantId")
        _require_hex64(self.vault_id, "vaultId")
        _require_hex64(self.generation, "generation")
        _require_hex64(self.operation_id, "operationId")
        _require_label(self.verifier_version, 64, "verifierVersion")
        _require_hex64(self.verifier_hex, "verifierHex")


def canonical_request_bytes(value: ProvisioningDigestInput) -> bytes:
    """Exact digest preimage. It does not define how verifier_hex is derived from the DELETE secret."""
    value.validate()
    out = bytearray()
    out.extend(_field(DOMAIN))
    out.extend(struct.pack(">I", value.version))
    out.extend(_field(value.service_id.encode("ascii")))
    out.extend(_field(value.tenant_id.encode("ascii")))
    out.extend(_field(value.vault_id.encode("ascii")))
    out.extend(_field(value.generation.encode("ascii")))
    out.extend(_field(value.operation_id.encode("ascii")))
    out.extend(_field(value.verifier_version.encode("ascii")))
    out.extend(_field(value.verifier_hex.encode("ascii")))
    return bytes(out)


def request_digest_hex(value: ProvisioningDigestInput) -> str:
    return hashlib.sha256(canonical_request_bytes(value)).hexdigest()


def _field(raw: bytes) -> bytes:
    if not 1 <= len(raw) <= 65535:
        raise ValueError("canonical field length out of range")
    return struct.pack(">H", len(raw)) + raw


def _require_hex64(value: str, name: str) -> None:
    if not _HEX_64.fullmatch(value):
        raise ValueError(f"{name} must be 64 lowercase hexadecimal characters")


def _require_label(value: str, max_chars: int, name: str) -> None:
    if not 1 <= len(value) <= max_chars or not _LABEL.fullmatch(value):
        raise ValueError(f"{name} is not canonical")
