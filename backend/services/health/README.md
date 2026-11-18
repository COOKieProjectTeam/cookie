# Health Data Service

Manual, onboarding and imported weight writes carry the UTC offset of the
measurement location in minutes. Health derives and stores the local date
before normalizing the instant. Offset corrections are part of the weight
revision and signed assessment input. Weight v2 carries the stable date and
source. Account activation and connection transitions emit a versioned count
of active connections, independent of the presence of measurements.

Health owns weight, daily activity and nutrition assessments. See the
[accepted policy and invariants](../../../docs/adr/0016-health-data-service.md).
User owns accepted goals. Its acceptance returns 202 and an attempt URI.

## Local operation

Build with `./gradlew :backend:services:health:bootJar`. Configure a dedicated
PostgreSQL database and create the non-owner role `health_app` before migrating.
Use `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER` and `SPRING_FLYWAY_PASSWORD` with
`java -jar backend/services/health/build/libs/health.jar --cookie.health.mode=migrate`.
If provisioning the runtime role later, the owner calls
`SELECT health_apply_runtime_grants()`.

Run the same JAR without the mode flag as `health_app`. Configure
`COOKIE_HEALTH_DATABASE_URL`, `COOKIE_HEALTH_DATABASE_USER` and
`COOKIE_HEALTH_DATABASE_PASSWORD`. Use `SPRING_PROFILES_ACTIVE=dev` locally,
point `COOKIE_IDENTITY_JWKS_URL` at the local Identity instance and set
`COOKIE_NATS_URL` to a JetStream broker. Set `COOKIE_NATS_PROVISION_LOCAL=false` in Health and
User when an administrator provisions streams and durables separately. The
Health role may publish its three descriptor subjects and use only
`$JS.API.CONSUMER.INFO.COOKIE_EVENTS.health-lifecycle-v1`,
`$JS.API.CONSUMER.MSG.NEXT.COOKIE_EVENTS.health-lifecycle-v1`,
`$JS.ACK.COOKIE_EVENTS.health-lifecycle-v1.>` and its subscription inbox
`_INBOX.cookie.health.>`. User uses its own durable and inbox. Infrastructure
provisions exact publish subjects from service descriptors. Health listens on 8084 by default.
Readiness requires migrated PostgreSQL and a usable Identity key set. Only
the direct operator listener exposes `/healthz` and `/readyz`.

To use the local edge, start Identity on 8080, User on 8083 and Health on 8084.
Run Caddy with the local config:

```bash
docker run --rm --add-host=host.docker.internal:host-gateway -p 127.0.0.1:8085:8085 \
  -v "$PWD/deploy/docker/health.Caddyfile:/etc/caddy/Caddyfile:ro" caddy:2
curl --fail http://localhost:8085/v1/health/weight -H "Authorization: Bearer $COOKIE_ACCESS_TOKEN"
```

## Assessment keys

Health uses an ES256 private JWK with a `kid` at `COOKIE_HEALTH_RECEIPT_KEY_PATH`.
User and Health use the corresponding public JWKS at
`COOKIE_HEALTH_RECEIPT_VERIFICATION_KEYS`. For each service, configure its
distinct RSA-OAEP-256 private JWKS at `COOKIE_HEALTH_ASSESSMENT_DECRYPTION_KEYS`
or `COOKIE_USER_ASSESSMENT_DECRYPTION_KEYS`. Mount the corresponding public
JWKS in the other service via `COOKIE_HEALTH_ASSESSMENT_ENCRYPTION_KEYS` and
`COOKIE_USER_ASSESSMENT_ENCRYPTION_KEYS`. Order the active encryption key first.
Retain old verification and decryption keys while attempts or broker messages
still reference them. Development can use an ephemeral signing key for
preview-only smoke tests.

## Imports

Use UUIDv4 Idempotency-Key for mutations. An exact replay of a committed import
sequence uses its original key. Persist the complete request before sending it.
After 30 days a replay returns a minimal acknowledgement. Resume with the
existing connection ID only if the client can establish that it is the same
platform store. Start new writes in the returned generation. A single batch
accepts 1–100 operations and up to 256 KiB. An invalid operation rolls back the
batch. Deleted weight identities remain terminal until the connection is purged.
Disconnection preserves history.

## Verification

```bash
./gradlew :backend:services:health:check :backend:services:health:integrationTest
```

Integration tests require Docker. They use a non-owner runtime role and
exercise batch rollback, fencing, cleanup, ownership, deletion and publication
against PostgreSQL and JetStream.
