from __future__ import annotations

from typing import Mapping

from reference_server.auth import (
    AuthorizationError,
    DeleteCapabilityPrincipal,
    ProvisioningPrincipal,
)


class StaticTestProvisioningAuthenticator:
    def __init__(self, credentials: dict[str, ProvisioningPrincipal]) -> None:
        self.credentials = dict(credentials)

    def authenticate(self, headers: Mapping[str, str], *, right: str, service_id: str, now_epoch_seconds: int) -> ProvisioningPrincipal:
        credential = headers.get("Authorization", "")
        principal = self.credentials.get(credential)
        if principal is None:
            raise AuthorizationError("unknown test provisioning credential")
        return principal


class StaticTestDeleteAuthenticator:
    def __init__(self, credentials: dict[str, DeleteCapabilityPrincipal]) -> None:
        self.credentials = dict(credentials)

    def authenticate(self, headers: Mapping[str, str], *, service_id: str) -> DeleteCapabilityPrincipal:
        credential = headers.get("Authorization", "")
        principal = self.credentials.get(credential)
        if principal is None:
            raise AuthorizationError("unknown test DELETE capability")
        return principal
