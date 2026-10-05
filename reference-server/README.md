# Resilience Vault — local DELETE reference server

This directory is a **non-production reference model** for DELETE provisioning and deletion state.
It exists to exercise transaction boundaries and access-control separation before any hosting,
identity provider, service origin, or production credential is selected.

## Safety properties

- Python standard library only; SQLite is the transactional store.
- No production service identity, tenant, credential, endpoint, or verifier implementation.
- The HTTP server binds only to `127.0.0.1`; `0.0.0.0` is rejected.
- `ProvisioningAuthenticator` and `DeleteCapabilityAuthenticator` deny by default.
- Fake principals/capabilities live only in `reference_server.testing` and test code.
- The server never logs request headers, bodies, tokens, or capability material.
- DELETE capability authorization is injected; this reference server deliberately does **not**
  define how a 32-byte DELETE secret is transformed into a production verifier.
- Tombstone and purge outbox are committed transactionally. A distinct SQLite tombstone ledger is
  attached to the transaction and is intentionally modeled as surviving a restore of the primary
  mutable-data database.

The separate ledger is a **reference assumption**, not a claim about a future provider's backup
semantics. A production backend must define storage replication, backup scope, retention, restore,
and anti-rollback guarantees explicitly.

## Tests

```bash
python3 -m unittest discover -s tests -v
```

No network access is required. HTTP integration tests start a loopback server on an ephemeral port.
