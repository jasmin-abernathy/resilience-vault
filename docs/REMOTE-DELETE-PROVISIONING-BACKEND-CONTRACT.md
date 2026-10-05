# Remote DELETE provisioning — backend contract still required

This document is intentionally a list of unresolved production requirements. The Kotlin state
machine models restart/reconciliation behavior only; it is **not** a backend implementation and
must not be used to enable `CONFIGURED`.

## Production remains blocked

Until every item below has a reviewed answer:

- `ProductionApprovedRemoteServiceRegistry` stays empty;
- no provisioning HTTP adapter is added;
- no provisioning credential is issued or stored;
- `ActiveVaultAuthority.CONFIGURED` must remain unreachable from production wiring;
- upload and CONFIGURED SMS arming remain disabled;
- `PRODUCTION_CRYPTO_READY=false` and `SMS_REMOTE_PANIC_READY=false` remain unchanged.

## Provisioning authentication

Define a credential that is distinct from READ, upload and DELETE capabilities.

Required answers:

- who authenticates the user/device to obtain it;
- exact scope: tenant, service, vault, generation and operation;
- lifetime and renewal behavior;
- persistence rules across process death/reboot;
- behavior after panic destroys READ capability;
- separate authentication for abandoning/revoking an interrupted operation.

## Idempotent provisioning operation

The client contract assumes a stable random `operationId` and a stable `requestDigest` are fixed
before the first request.

The backend must define:

- exact request fields covered by `requestDigest`;
- same operationId + same digest => same durable result;
- same operationId + different digest => permanent conflict;
- retention duration for the idempotency record;
- behavior after retention expires while the client is still unresolved;
- how an operator can reconcile an expired/ambiguous operation without automatically creating a
  new DELETE capability.

A timeout is an unknown result, never proof of failure.

## Status / reconciliation endpoint

An authenticated status read must return a durable binding without returning the DELETE secret.

It must bind at least:

- operationId;
- requestDigest;
- serviceId;
- tenantId;
- vaultId;
- vault generation;
- durable server state.

The server must never re-emit the raw DELETE capability as a recovery mechanism.

## Client-generated DELETE capability

The target protocol currently prefers a 32-byte CSPRNG capability generated client-side and sealed
locally before the first request. The server should retain only a verifier bound to the operation
and vault tuple.

This remains a protocol design pending independent review. Do not implement custom cryptography or
a home-grown verifier construction merely from this document.

## Commit proof

Define what authenticated server evidence is sufficient for the client to advance from PREPARED to
SERVER_CONFIRMED and eventually publish CONFIGURED.

A transport status code alone is insufficient. Evidence must bind the operation, digest and vault
tuple and represent a durable server result.

## Abandon / revoke

If panic or another fail-closed condition occurs while provisioning is unresolved:

- local panic must continue immediately;
- a late server response must not publish CONFIGURED;
- the unresolved operation remains `ABANDONED_NEEDS_RECONCILIATION`;
- any server-side revoke must be separately authenticated, idempotent and queryable after crash.

Specify whether a server operation that completed before panic can be revoked and what durable
attestation proves that revocation.

## DELETE completion attestation

The existing remote-delete coordinator treats generic/ambiguous replies as non-complete.

The backend must specify an authenticated completion response (or equivalent status read) binding:

- operationId;
- serviceId / tenant;
- vaultId / generation;
- durable tombstone/delete state;
- response version.

HTTP 200/204 alone is not sufficient proof. 202 is pending only if explicitly defined by the
protocol. Generic 404 remains ambiguous.

## Network policy

Before adding an adapter, define:

- the production `serviceId` and exact approved origin/route;
- TLS policy and whether public-key/certificate pinning is operationally supportable;
- redirects (expected default: reject);
- connect/read/write/overall timeout budgets;
- maximum request and response sizes;
- exact media types and response versioning;
- retry/backoff limits;
- proxy/VPN expectations if any.

No host, URL or redirect target may come from a persisted intent or server response.

## Anti-rollback and physical gates

Checksums only detect accidental corruption. Coordinated rollback of local files remains unresolved
and must be addressed before production activation, after the server transaction is specified and
independently reviewed.

Physical testing remains mandatory on API 26–29 and 30+, including reboot, screen lock, process
kill at each journal boundary, low disk space, concurrent panic, Keystore behavior and network
failure at every server boundary.
