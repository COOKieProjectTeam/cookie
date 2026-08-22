# Identity Service

Identity v1 supports email/password registration, email confirmation, login,
refresh rotation, idempotent logout and ES256 JWKS. The public transport is
generated from the service-owned `contracts/openapi/public/identity.yaml`; generated
sources live under `build/generated` and are never committed.

## Local run

The complete development stack is started from the repository root:

```bash
make compose-up
```

- API: `http://localhost:8080/v1/auth/...`
- liveness/readiness: `http://localhost:8080/healthz`, `/readyz`
- Mailpit UI: `http://localhost:8025`
- NATS monitoring: `http://localhost:8222`

Identity never sends email directly. It writes an encrypted
`notification.email.requested` event to its transactional outbox; the local
Notification sink decrypts the compact JWE and delivers it to Mailpit.

The code has explicit hexagonal boundaries: `domain` contains aggregates,
value objects and business invariants; `application` contains use cases and
input/output ports; the Spring service module contains HTTP, JDBC,
cryptography and messaging adapters. Domain and application do not depend on
Spring, JDBC, Jackson, Nimbus or NATS.

## Key configuration

Without the `dev` or `test` Spring profile, Identity requires mounted JSON Web
Key files and never generates or writes key material:

- `COOKIE_IDENTITY_JWT_PRIVATE_KEY_PATH`: private P-256 JWK with `kid`,
  `use=sig` and `alg=ES256`;
- `COOKIE_IDENTITY_JWT_RETIRING_PUBLIC_KEY_PATHS`: comma-separated public P-256
  JWK files with `use=sig` and `alg=ES256`, retained during verification-key
  rotation;
- `COOKIE_NOTIFICATION_PUBLIC_KEY_PATH`: public-only Notification Service RSA
  JWK (at least 2048 bits) with `use=enc` and `alg=RSA-OAEP-256`.

Private signing/decryption material must not be committed. Ephemeral generation
and `COOKIE_DEV_NOTIFICATION_PRIVATE_KEY_OUTPUT_PATH` are available only in the
`dev` and `test` profiles; local compose enables `dev` explicitly.

Production NATS is deployment-owned: Identity neither creates nor mutates the
shared stream outside `dev`/`test`. Production startup requires a `tls://` URL,
credentials, a dedicated JKS truststore and its password via `COOKIE_NATS_URL`,
`COOKIE_NATS_CREDENTIALS_PATH`, `COOKIE_NATS_TRUSTSTORE_PATH` and
`COOKIE_NATS_TRUSTSTORE_PASSWORD`. The credential may publish only the two
subjects in `service.yaml` and subscribe only to
`_INBOX.cookie.identity.>` for JetStream acknowledgements; it receives no event
subscriptions or `$JS.API` stream-management permissions.

An older development Notification JWK without the required `use`/`alg` metadata
is rejected intentionally. Migrate that file while preserving the RSA key, or
reset the dev key together with PostgreSQL and NATS volumes; deleting the key
alone makes already encrypted verification messages undecryptable.

This pre-release change rewrites the unpublished `V001`/`V002` baseline. An
existing local Identity database must therefore be recreated rather than
Flyway-repaired. Once the first environment is promoted, applied migrations are
immutable and every schema change must use a new migration version.

## Key rotation

Publish the next ES256 public key to every replica before switching the active
signer. Keep the previous public key until at least access-token TTL + JWKS
cache TTL + rollout/clock-skew margin after the final token signed with it.
Notification private keys must remain available while any outbox/JetStream
message encrypted to their `kid` can still be delivered; rotation therefore
requires a key ring or a drained queue, not overwriting the only private key.
