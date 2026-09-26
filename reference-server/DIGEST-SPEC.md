# Remote DELETE provisioning request digest — reference encoding V1

This file specifies only the **request digest encoding**. It does **not** define the cryptographic
construction of the DELETE capability verifier. `verifierHex` is an opaque, already-derived verifier
value supplied to this encoding after an independently reviewed verifier scheme exists.

## Domain and algorithm

Digest algorithm: SHA-256.

Digest preimage fields, in exact order:

1. `DOMAIN = ASCII("RV-DELETE-PROVISIONING-REQUEST-V1")`
2. `version = uint32 big-endian`, currently `1`
3. `serviceId`
4. `tenantId`
5. `vaultId`
6. `generation`
7. `operationId`
8. `verifierVersion`
9. `verifierHex`

Every byte-string field, including `DOMAIN`, is encoded as:

```text
uint16_big_endian(length_in_bytes) || bytes
```

All string fields are canonical ASCII in V1. `vaultId`, `generation`, `operationId`, and
`verifierHex` are exactly 64 lowercase hexadecimal characters. `serviceId`, `tenantId`, and
`verifierVersion` use `[a-z0-9][a-z0-9._:@-]*` within their specified length limits.

The canonical request digest is:

```text
hex_lower(SHA-256(preimage))
```

Do **not** derive this digest from JSON key order, whitespace, serializer behavior, HTTP headers, or
URLs.

## Test vector V1

Input:

```text
serviceId       = test-primary
tenantId        = tenant-1
vaultId         = 1111111111111111111111111111111111111111111111111111111111111111
generation      = 2222222222222222222222222222222222222222222222222222222222222222
operationId     = 3333333333333333333333333333333333333333333333333333333333333333
verifierVersion = opaque-v1
verifierHex     = 4444444444444444444444444444444444444444444444444444444444444444
version         = 1
```

Expected digest:

```text
f22cbfa8212e00b9643eb70d0e58315129b8556bc31e2c1d06e9fb68fd197641
```

The verifier name `opaque-v1` in this vector is a **test/reference label only**. It is not an
approved production verifier scheme.
