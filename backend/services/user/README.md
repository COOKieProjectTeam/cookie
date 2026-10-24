# User Service

Created with the repository service template. Owns profile, dietary preferences,
allergies, immutable nutrition/hydration goal revisions and daily goal overrides.
Identity owns accounts and credentials; Health owns weight measurements.

## Behavioral invariants (implementation contract)

1. Every public operation derives its account exclusively from a verified Identity
   ES256 access token. Caller-supplied identifiers cannot select another account.
2. Activation or the first verified Identity request creates at most one incomplete profile. Deletion and every mutation
   serialize on the same account lock. An append-only deletion tombstone rejects
   valid but stale tokens and prevents late activation/events from restoring data.
3. Profile updates compare the supplied version under lock. Concurrent updates
   cannot silently overwrite each other. Each successful mutation and its outbox
   events commit together; rollback produces neither state nor events.
4. Nutrition and hydration goals are immutable revisions. Current means the newest
   revision effective on or before today's date in the profile's IANA timezone.
   Equal-date revisions use a monotonic sequence, never timestamp ties.
5. A daily override belongs only to its account/date; at most one CHEAT_DAY exists
   per account/calendar month, enforced by PostgreSQL under competing requests.
6. Retried commands with the same account, operation and idempotency key return
   the original response. A changed payload with that key conflicts. Evidence is
   deleted with the account; tombstones still reject subsequent replay.
7. Inbox, lifecycle effects and resulting events share one PostgreSQL transaction;
   broker ACK follows commit. Invalid envelopes are quarantined as metadata only.
8. Outbox publication uses fenced leases and broker acknowledgements. Independent
   events continue after an individual failure, with bounded exponential retry.
9. user.profile.updated is a complete allowlisted snapshot with a positive
   monotonic aggregate_version compatible with Notification. Notification's
   explicitly chosen timezone/locale retain their existing ownership.
10. Runtime grants cannot rewrite/delete lifecycle tombstones or change schema,
    migrations or immutable event payloads. No cross-service database access.

Automatic nutrition calculation belongs to Health. The planned assessment and
idempotent weight APIs must exist before automatic onboarding can be activated.

## Run and migrate

Build: `./gradlew :backend:services:user:bootJar`. Run the resulting
`backend/services/user/build/libs/user.jar` with `COOKIE_USER_DATABASE_URL`,
`COOKIE_USER_DATABASE_USER=user_app`, `COOKIE_USER_DATABASE_PASSWORD`,
`COOKIE_IDENTITY_JWKS_URL`, `COOKIE_IDENTITY_ISSUER`, `COOKIE_IDENTITY_AUDIENCE`.
Use `PORT=8083` when running alongside Identity and Notification locally.
`SPRING_PROFILES_ACTIVE=dev` permits a local HTTP JWKS origin and non-TLS NATS;
production requires TLS, workload `.creds` and JKS broker trust configuration.

Provision a dedicated database owned by a migration role and a non-owner
`user_app` LOGIN role. Run the same artifact with `--cookie.user.mode=migrate`
and `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD` before
starting runtime. Migrations apply the explicit runtime grants if `user_app`
exists. If the role is created later, the owner runs
`SELECT user_apply_runtime_grants();`. Runtime has Flyway disabled and must not
receive the owner credential. A failed migration blocks runtime rollout.
Production deployment/ingress is intentionally not activated by this release.

Production broker administration must provision COOKIE_EVENTS and durable
`user-lifecycle-v1` with explicit ACK, DeliverAll, ackWait 60s, maxAckPending 100,
maxDeliver -1 and filters for account.activated.v1/account.deletion.requested.v1.
User runtime publishes only the four `cookie.events.user.*.v1` subjects declared
in service.yaml. Its other publish grants are consumer INFO/NEXT and ACK subjects
for this durable; subscription is `_INBOX.cookie.user.>`. No STREAM.MSG.GET,
stream/consumer administration or foreign event publishing is authorized.
Dev/test alone provisions its bounded seven-day stream and durable automatically.

## Operational limits

No Redis or shared database is used. JDBC pool max 12, connection acquisition 2s,
validation 1s, statement timeout 5s, lock timeout 2s, socket timeout 10s. HTTP
request bodies are bounded to 16KiB. JWKS complete request 3s, connection 2s,
response 64KiB, cache 5min, refresh minimum interval 30s. API auth failures are 401;
unavailable/expired key source is 503. Responses use Cache-Control: no-store.

Every read checks deletion and can initialize an incomplete profile under the
account lock from the authenticated JWT, so activation stream retention cannot
strand old active accounts. An activation event then becomes a no-op. Default
UTC/ru are explicit; Notification still owns its scheduling timezone. User writes
manual goals without pretending that onboarding or Health calculation completed.

`cookie.user.outbox.pending`, `cookie.user.outbox.oldest.age.seconds`, inbox/outbox
failure counters and quarantine count expose degraded delivery. Metrics health=0
means cached values are stale. Published payloads, inbox and quarantine are retained
conservatively; operator maintenance must preserve at least 30 days of inbox metadata
and must never remove tombstones. No retention claim implies unlimited broker replay.

## Events

All User events use aggregate_type=user, aggregate_id=userId, positive monotonic
aggregate_version and v1 subjects. `user.profile.updated` contains only userId,
changedFields, timezone, locale, onboardingCompleted; no birth date/diet/allergies.
Goal changes carry a complete immutable nutritionGoalVersion (id, effectiveFrom,
revision, source, value with nutrients/optional weight targets). Override changes
carry localDate and full override or explicit null on deletion. Hydration changes
carry targetMl and effectiveFrom. Consumers compare versions; delivery may reorder.

## Evidence

`UserPersistenceIntegrationTest`: real PostgreSQL and actual user_app grants,
concurrent optimistic writes/cheat-day/idempotency, history, isolation, deletion,
late replay, rollback and reviewer regression for immutable evidence.
`UserRuntimeIntegrationTest`: actual User Spring HTTP runtime, PostgreSQL, JetStream,
two signed fixture users, strict binding, broker pause/recovery, durable events and
quarantine, inbox-confirmed lifecycle processing, deletion/stale JWT rejection,
numeric contract replay and real User → Notification locale propagation.
`UserMigrationIntegrationTest` executes the migration JAR on a V1 database with
representative data and converges existing broad runtime grants.
JWKS and Identity lifecycle producer are fixtures: this is **not** full
Identity E2E. `IdentityTokensTest` verifies token profile/cache failures;
`UserDomainTest` verifies quantity/patch/override boundaries.
`UserContractTest` compares locale and numeric boundary acceptance in the public
request/response schemas with the domain, including redundant trailing zeros.

See ADR 0015 and the revision-specific review report for remaining dependencies,
checks and independent review. Automatic onboarding remains planned pending Health.
