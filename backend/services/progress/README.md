# Progress Service

Progress owns diary, weight and achievement projections in its PostgreSQL
database. See [ADR 0017](../../../docs/adr/0017-progress-day-boundaries.md)
for commit evidence, ownership and calendar semantics.

- Inbox and projections commit together under an account lock. A source's
  sequence advances across complete commit groups only.
- A delayed event may correct a displayed fact after closing. A confirmed
  result remains immutable and counts once per account and calendar date.
- Closing waits for User and Nutrition evidence. Health and Notification
  are independent of achievement certification.
- Account deletion uses the same locks as writes, workers and authenticated
  reads. Data erasure and the deletion tombstone commit together.

Domain tests run with `./gradlew :backend:services:progress:domain:test`.
Run the PostgreSQL tests with `./gradlew :backend:services:progress:integrationTest`.

## Local runtime

Build the service with `./gradlew :backend:services:progress:bootJar`.
The local Compose overlay provisions an owned database, runs migrations,
and gives public Progress routes a loopback ingress on port 8086.

```bash
docker compose -f deploy/docker/compose.yaml -f deploy/docker/progress.compose.yaml up --build progress-gateway
```

Run User and Nutrition with their separate capture workers, and join them
and Health to the same JetStream stream to populate the projection. See the
owner READMEs for slot initialization and credentials. Public reads use
Identity access JWTs. The gateway allows only the three public Progress
routes. Probes belong to the service network.

## Mobile BFF

The generated JVM client is in `backend/clients/progress`. It targets
`GET /internal/v1/progress/today` directly and sets two-second connection
and read timeouts. Configure `COOKIE_PROGRESS_WORKLOAD_ISSUER` and
`COOKIE_PROGRESS_WORKLOAD_JWKS_PATH` on the callee. Use independent
short-lived ES256 `workload+jwt` credentials for subject `mobile_bff` and
audience `progress`. Forward the delegated Identity access JWT separately.
The internal endpoint stays within the operator/service network.

See [recovery and operations](ops/recovery.md) for owner cut/replay, broker
retention and the read guard. Run the process acceptance path with
`./gradlew :backend:services:progress:processE2e`.
