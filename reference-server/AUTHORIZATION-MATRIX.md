# Reference server authorization matrix

All authenticators deny by default. The table below describes only the reference API contract.
Concrete production authentication is intentionally undefined.

| Route | Provision right | Status right | Revoke right | DELETE capability |
| --- | --- | --- | --- | --- |
| `PUT /v1/delete-provisionings/{operationId}` | allow | deny | deny | deny |
| `GET /v1/delete-provisionings/{operationId}` | deny unless principal also has status | allow | deny | deny |
| `POST /v1/delete-provisionings/{operationId}/revoke` | deny unless principal also has revoke | deny | allow | deny |
| `DELETE /v1/vault-generations/{vaultId}/{generation}` | deny | deny | deny | allow exact tenant/service/vault/generation only |

A `ProvisioningPrincipal` additionally requires:

- authenticated tenant equal to the requested tenant;
- unexpired principal;
- audience equal to the configured reference service;
- explicit right for the invoked route.

A DELETE capability is never accepted as an account/provisioning session and cannot GET, LIST,
upload, provision, revoke, or query provisioning status.

No READ or upload routes are implemented by the reference server. Their absence is intentional;
those principals and policies remain separate future contracts.
