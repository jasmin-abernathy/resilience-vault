# Production decisions intentionally unresolved

The local reference server must not be used to infer any of these values.

## Identity and authentication

- identity provider and enrollment process;
- provisioning credential issuance, renewal, revocation and recovery;
- actual tenant identity source and operational ownership;
- upload/read principal mechanisms;
- production DELETE verifier construction and independent security review.

## Hosting and storage

- production host/origin and `serviceId`;
- database engine and high-availability model;
- object storage provider, versioning, staging and replication;
- whether the tombstone authority is physically/logically isolated from ordinary data restores;
- retention duration for operation idempotency records;
- backup windows, backup retention and restoration procedures;
- guarantees required before reporting DELETE COMPLETE.

## Network and protocol

- TLS trust/pinning decision;
- redirect policy (reference assumption: reject);
- proxy/VPN policy;
- validated connect/read/write/overall timeout budgets;
- response/request size limits based on real deployment behavior;
- media types/versioning/error schema;
- authenticated commit/status/delete attestations.

## Recovery and anti-rollback

- behavior after idempotency retention expires while a client remains unresolved;
- operator/human reconciliation process;
- server-side revocation proof for abandoned provisioning;
- anti-rollback design spanning Android state and backend state;
- backup restore rules proving tombstones cannot be lost or bypassed.

Until these decisions have named operational owners and reviewed implementations, Android
`CONFIGURED`, production networking, upload activation and both security gates remain blocked.
