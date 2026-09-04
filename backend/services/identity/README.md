# Identity Service

Identity v1 supports email/password registration, email confirmation, login,
idempotent refresh rotation, idempotent logout and ES256 JWKS. The public transport is
generated from the service-owned `contracts/openapi/public/identity.yaml`; generated
sources live under `build/generated` and are never committed.

Email confirmation is an idempotent state transition and does not issue a
session. After a successful `204`, the client uses the regular email/password
login endpoint. Repeating the same successfully redeemed confirmation token is
a no-op `204` while its audit record is retained for at least 30 days, so a lost
response cannot strand an unrecoverable refresh secret. After that bounded
retention window the token is invalid again.

The canonical BCP 47 locale selected at registration is part of one pending
`RegistrationAttempt`, so verification resends add child tokens without copying
the password hash and keep the original language. The attempt lives for 24 hours;
each email token lives for 30 minutes and expiry checks never depend on cleanup.
On natural expiry, or when another attempt for the same email wins, the attempt
becomes an abandoned tombstone: password hash and locale are scrubbed atomically,
while id/proof fingerprint and token evidence remain for at least 30 days. Thus
maintenance cannot turn an exact retry into a new email during that bounded window.

Before the first register call, a client must atomically persist a cryptographically
random `registrationAttemptId` together with its 32-byte `registrationProof` in
Keychain/Keystore. Network retries repeat the identical id, proof, email, password
and locale; changing payload under the same id/proof returns `409`. An email token
has the form `v1e.<attempt-id>.<token-id>.<secret>`, so a deep link can select the
matching stored proof without putting that proof into the link. After a definite
or retried `204`, the client deletes the stored pair. Loss of the proof, use on a
second device, or deliberate restart requires a new id/proof and a new verification email;
the previous attempt remains unusable without its proof and is scrubbed when it expires
or when another attempt for the same email wins.

The in-service IP limiter runs at the application boundary, after JSON decoding.
Production ingress must also limit malformed JSON, slow connections and connection-level
abuse; the 16 KiB body cap bounds parsing cost but is not a complete DDoS perimeter.

Every logical refresh attempt sends a new UUID in `Idempotency-Key`; a network
retry repeats that UUID with the same refresh token. For 30 seconds Identity
returns the same successor refresh token while that successor remains current.
An exact retry that is too late or whose successor has already advanced is
rejected without revoking the session. Reuse of a redeemed credential with a
different key revokes the whole logical device session.

Within the supported `.ru`/`.рф` provider set, email local parts are a
case-insensitive product identifier and are stored in lower case. Providers
that distinguish local-part case are outside this contract.

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

Retention runs every minute, drains all owned tables fairly in configurable
batches, and has both batch-count and wall-time budgets. The scheduler has two
workers so broker acknowledgement latency cannot stall retention.

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
- `COOKIE_IDENTITY_TRUSTED_PROXY_CIDRS`: optional comma-separated ingress CIDRs
  whose `X-Forwarded-For` chain may be trusted. Leave empty for direct traffic.

Mutation request bodies are capped at 16 KiB before JSON parsing; oversized
requests receive `413 PAYLOAD_TOO_LARGE`.

Readiness waits at most the configured Hikari acquisition/validation bounds
(`COOKIE_IDENTITY_DATABASE_CONNECTION_TIMEOUT_MS`, default 2000, and
`COOKIE_IDENTITY_DATABASE_VALIDATION_TIMEOUT_MS`, default 1000) before returning
`503`, so a failed database cannot pin probe threads indefinitely.

Mutating transactions have a five-second statement/transaction deadline and a
two-second PostgreSQL lock deadline; JDBC socket reads are bounded to ten
seconds. All background JDBC statements also inherit a five-second PostgreSQL
statement deadline. Override transaction/lock bounds with
`cookie.identity.database.*` deployment properties
and `COOKIE_IDENTITY_DATABASE_SOCKET_TIMEOUT_SECONDS`, keeping the lock timeout
shorter than the transaction timeout.

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
