from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Protocol


class AuthorizationError(RuntimeError):
    pass


@dataclass(frozen=True)
class ProvisioningPrincipal:
    tenant_id: str
    rights: frozenset[str]
    expires_at_epoch_seconds: int
    audience: str

    def require(self, *, right: str, service_id: str, now_epoch_seconds: int) -> None:
        if now_epoch_seconds >= self.expires_at_epoch_seconds:
            raise AuthorizationError("provisioning principal expired")
        if self.audience != service_id:
            raise AuthorizationError("provisioning principal audience mismatch")
        if right not in self.rights:
            raise AuthorizationError("provisioning principal lacks required right")


class ProvisioningAuthenticator(Protocol):
    def authenticate(
        self,
        headers: Mapping[str, str],
        *,
        right: str,
        service_id: str,
        now_epoch_seconds: int,
    ) -> ProvisioningPrincipal: ...


class DenyAllProvisioningAuthenticator:
    def authenticate(
        self,
        headers: Mapping[str, str],
        *,
        right: str,
        service_id: str,
        now_epoch_seconds: int,
    ) -> ProvisioningPrincipal:
        raise AuthorizationError("reference server has no provisioning authenticator")


@dataclass(frozen=True)
class DeleteCapabilityPrincipal:
    tenant_id: str
    service_id: str
    vault_id: str
    generation: str


class DeleteCapabilityAuthenticator(Protocol):
    def authenticate(
        self,
        headers: Mapping[str, str],
        *,
        service_id: str,
    ) -> DeleteCapabilityPrincipal: ...


class DenyAllDeleteCapabilityAuthenticator:
    def authenticate(
        self,
        headers: Mapping[str, str],
        *,
        service_id: str,
    ) -> DeleteCapabilityPrincipal:
        raise AuthorizationError("reference server has no DELETE capability verifier")
