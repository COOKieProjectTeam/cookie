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
Push channels require their platform credentials; local email uses Mailpit.

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
