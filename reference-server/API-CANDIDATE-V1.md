# Local reference API candidate V1

This API is **not approved for production**. It is a loopback-only transaction model used to test
idempotence, authority separation, tombstones, purge outbox behavior, and crash/restart semantics.

All structured bodies are canonical JSON responses (`application/json`, sorted keys, no extra
whitespace) and are bounded to 8 KiB. Requests reject duplicate keys, unknown keys, wrong content
types, and oversized/truncated bodies.

## Provisioning

### `PUT /v1/delete-provisionings/{operationId}`

Requires the provisioning `provision` right. `tenantId` in the body must exactly equal the tenant
from the authenticated principal. First durable commit returns `201`; exact replay returns `200`;
conflicting operation/digest/binding returns `409`.

Request fields:

```text
version
serviceId
tenantId
vaultId
generation
operationId
requestDigest
verifierVersion
verifierHex
```

`verifierHex` is an opaque pre-derived verifier. This server does not define the verifier
cryptography. `requestDigest` is checked against `DIGEST-SPEC.md`.

### `GET /v1/delete-provisionings/{operationId}`

Requires the `status` right. Existing operations return the durable tuple, digest and state.
Missing/expired knowledge is represented as `UNKNOWN`; it is never proof that the server had no
effect.

### `POST /v1/delete-provisionings/{operationId}/revoke`

Requires the `revoke` right. Revocation is idempotent. A revoked provisioning cannot authorize a
future DELETE even if a test authenticator would otherwise return a matching DELETE principal.

## DELETE generation

### `DELETE /v1/vault-generations/{vaultId}/{generation}`

Requires an independently authenticated DELETE capability scoped to the exact tenant, service,
vault and generation.

First successful transaction atomically commits:

- durable tombstone;
- active-generation tombstone flag;
- purge outbox item;
- stable `deleteOperationId`.

Response is `202` + `PENDING` until active objects are gone. Replays return the same delete operation.
After purge completion, response is `200` + a structured `COMPLETE` body. This reference API never
uses bare `204` as proof of completion.

## Deliberately absent

There are no READ, LIST, upload, account-session, production authentication, production verifier,
or public-network routes. Internal upload/read/administrative-restore methods exist only to test
that every active-storage path consults the same durable tombstone authority.
