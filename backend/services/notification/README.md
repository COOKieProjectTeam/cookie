# Notification

Kotlin/JDK 25 Spring MVC service with framework-free `domain` and `application`
modules. Its PostgreSQL database owns inbox, preferences, device bindings,
minimal projections, deadlines and delivery state; Redis is a disposable index.

The authoritative behavior and event contracts are documented in
[Notification architecture](../../../docs/architecture/notification.md) and
[ADR 0013](../../../docs/adr/0013-notification-service.md).
The public contract is [notification.yaml](../../../contracts/openapi/public/notification.yaml).
Run commands below from the repository root.

```bash
make dev-up
./gradlew :backend:services:notification:check
./gradlew :backend:services:notification:integrationTest
```

Compose starts Identity, Notification, independent databases/roles in PostgreSQL,
JetStream, Redis and Mailpit. Notification is on `127.0.0.1:8082`, Mailpit on
`127.0.0.1:8025`. An explicit timezone is required before scheduled push is enabled.
Both Android and iOS push use FCM and require Firebase project/service-account
credentials. For iOS, configure the APNs authentication key in Firebase, not in
Notification. Both platforms register opaque FCM tokens; V006 disables legacy iOS
APNs bindings until the client re-registers. Local email uses Mailpit.
V007 enforces one active FCM-token owner across platforms. V008 grants runtime
only named application tables, append-only account tombstones and age-bounded
quarantine retention; privileged replay uses a separate database role.
Production runtime and replay never receive the migrator password. A one-shot
`--cookie.notification.mode=migrate` process applies Flyway using only migration
credentials, after old workers stop and before the new runtime starts. It loads
`notification-migrate.yaml` and exits nonzero on failure; it creates no web server,
consumer, scheduler or delivery provider. Production runtime disables Flyway.
`--cookie.notification.mode=replay` loads `notification-replay.yaml` and only the
JDBC/event-handler/operator-NATS beans: it needs no SMTP, FCM, JWE or workload NATS
credentials and never applies migrations. The local `dev` profile keeps startup
migrations for `make dev-up`.

Review regressions cover cross-platform ownership and concurrent transfer,
key-specific email failure isolation, timezone changes for queued/expired daily
jobs, and actual runtime permissions after migration and permission reconvergence.
`NotificationReviewRegressionIntegrationTest` normally starts disposable PostgreSQL.
For a test-owned local PostgreSQL only, `COOKIE_NOTIFICATION_TEST_DATABASE_URL`
can point to a database accessed as `postgres` with no password; the fixture creates
roles and temporary schemas. Never point it at a shared or production database.

The integration task requires a working Docker-compatible daemon. It builds and
starts the real Identity and Notification artifacts, then checks registration,
encrypted event consumption, SMTP, confirmation, JWT API access and deletion.
It also verifies rollback before ACK, duplicate events, independent accounts,
token transfer, versioned projections, concurrent workers, Redis recovery,
delivery leases and terminal SMTP `UNKNOWN`. Future domain producers use synthetic
contract fixtures; no replacement producers are deployed.

Deployment, channel enablement, operator replay, key rotation and independent
image rollback are in the [production runbook](../../../deploy/production/README.md).
Production requires real SMTP credentials and a verified HTTPS link origin with
the mobile handler. Provider acceptance is recorded as `ACCEPTED`; it does not
prove that a message was displayed or read.
