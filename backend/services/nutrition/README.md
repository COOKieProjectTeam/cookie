# Nutrition

Autonomous Kotlin service on the Identity/platform foundation from main. Owns
meals, editable food snapshots, saved dishes and hydration. No Food Catalog read
is needed to record a client-provided snapshot. Recipe import and Media attachments
remain planned until their authenticated internal contracts and services exist.

## Behavioral invariants (implementation acceptance criteria)

1. Only a verified Identity ES256 access token selects an account; another user's
   object ID always returns 404. A deletion tombstone prevents recreation.
2. A write and all resulting outbox events commit in one PostgreSQL transaction.
   Per-account transaction locks serialize read/modify/write, deduplication and
   deletion; no broker/network request occurs under that lock.
3. Meal/water client IDs are account-scoped. Exact retries return the current
   record, changed requests conflict, and deleted keys cannot resurrect data.
4. Updates/deletes require the current version. Child edits increment the meal
   version; meal deletion publishes a strictly newer tombstone.
5. Quantities/nutrients are finite, bounded and nonnegative (amounts positive).
   PIECE uses a positive serving base amount, G/ML must match the base unit.
   Nutrition uses decimal arithmetic rounded half-up to three places; unknown
   macros stay unknown in totals. Source is immutable provenance, not authority.
6. Local dates match timestamps in an explicit IANA timezone. Moving a record
   emits fresh snapshots for both old and new days. Empty water days publish
   totalMl=0 and an explicit null lastConsumedAt; versions survive empty days.
7. Saved-dish consumption creates independent item/dish IDs and snapshots;
   later template changes never mutate diary history.
8. Outbox leases fence stale workers. Broker ACK precedes published state;
   recovery may redeliver with the same event ID. Consumers must deduplicate and
   reject older aggregate versions. Independent events continue after failure.
9. Account deletion commits inbox deduplication, minimal append-only tombstone
   and removal of all owned diary data before broker ACK. It does not invent an
   Identity deletion-completion protocol.

## Explicit follow-ups

- Complete handwritten Nutrition flows/facts and topology in the HTML atlas.
  Notification #20 is now in main; this rebase refreshes the generated contract
  snapshot for its mandatory CI check. Full map integration remains a follow-up.
- Execute Nutrition → Notification end-to-end tests now that Notification is in main.
  Producer PostgreSQL/outbox/JetStream tests and Notification contract assertions
  are narrower evidence, not a verified end-to-end integration.
- Recipe and Media integrations require their real services and contracts.
- Production rollout is outside this PR; do not deploy or merge automatically.

## Build and verification

```sh
./gradlew :backend:services:nutrition:check :backend:services:nutrition:integrationTest
./gradlew validateServiceDescriptors validateBundledPublicOpenApi compileKmpPublicClient
```

Integration tests require Docker-compatible containers and fail if unavailable.
They migrate a fresh PostgreSQL 18 database, run HTTP/business operations under
`nutrition_runtime`, and publish/consume through NATS JetStream. No Notification
process is instantiated. Tests assert the meal/water requirements documented by
PR #20 (`docs/architecture/notification.md` and `NotificationEventHandler`).

## Runtime and permissions

Set `COOKIE_NUTRITION_DATABASE_URL`, `COOKIE_NUTRITION_DATABASE_USER`,
`COOKIE_NUTRITION_DATABASE_PASSWORD`, `COOKIE_IDENTITY_JWKS_PATH`, and
`COOKIE_NATS_URL`. Production NATS additionally requires the credential file,
truststore and password configured in `application.yaml`; peer names are verified.
The Identity public JWKS mount is read on each verification, so an atomic file
replacement applies key rotation without restart. Keep current and retiring
public keys. Nutrition never accepts token-controlled key URLs or private keys.

The application defaults to Flyway disabled. Apply the versioned migrations as
schema owner, create a separate `nutrition_runtime` login, then apply
`ops/runtime-grants.sql` as owner. Runtime cannot change schema, Flyway history,
or update/delete account tombstones. The `dev` profile permits automatic
migration and local stream/consumer provisioning; it is not a production profile.

Provision stream `COOKIE_EVENTS` with `cookie.events.>` and durable consumer
`nutrition-account-deletion-v1`, filter
`cookie.events.account.deletion.requested.v1`, explicit ACK, 30-second ACK wait,
and max 20 unacknowledged messages. Production runtime must only publish
the five exact subjects `cookie.events.nutrition.meal.created.v1`,
`cookie.events.nutrition.meal.updated.v1`, `cookie.events.nutrition.meal.deleted.v1`,
`cookie.events.nutrition.day.changed.v1`, and `cookie.events.nutrition.water.changed.v1`;
use its `_INBOX.cookie.nutrition.>` replies,
pull/ACK its own consumer, and bind/read consumer information. It does not need
stream administration or arbitrary stream-message retrieval. Operator replay
must use separate credentials; metadata-only rejection rows identify originals.

`/healthz` is process liveness; `/readyz` checks PostgreSQL and public verification
keys. Probes are excluded from the public bundle and should stay off public
ingress. Broker outages preserve readiness because writes remain durable in the
outbox; monitor pending rows/age and `cookie.nutrition.outbox.failure`,
`cookie.nutrition.outbox.published`, `cookie.nutrition.consumer.failure`,
`cookie.nutrition.consumer.retry`, and `cookie.nutrition.consumer.rejected`.
Prometheus/Grafana deployment remains the platform's separate telemetry decision.

API details and limits are in `contracts/openapi/public/nutrition.yaml`.
See [ADR 0014](../../../docs/adr/0014-autonomous-nutrition-service.md) for storage,
version/date semantics, retry behavior and the explicit integration deferrals.

## Local port after Notification merge

The shared Compose stack exposes Notification on localhost:8082. Run Nutrition
with `PORT=8083` when using that stack. Nutrition is not yet wired into the shared
Compose file: provide its own database and public Identity JWKS mount as above.
No additional domain service is needed for manual diary/water HTTP smoke tests.
