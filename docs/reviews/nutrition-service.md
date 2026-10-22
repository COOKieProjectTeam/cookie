# Nutrition implementation review evidence

PR: #21, independent `feat/nutrition-service` against `main`.
Current base: `bf0ad8b270306c469df200effe929a694db8d860` (Notification #20 merged).
Review corrections compare against `7f2a7fe9b66a17663b5ef78e08b55016d1434d2c`.
The correction commit contains this report; its exact SHA and CI run are recorded
in the PR description. No Notification production implementation is changed.

## Current guarantees and executed evidence

| Invariant | Implementation | Behavioral evidence | Result / limits |
|---|---|---|---|
| Verified Identity subject and ownership | AccessTokens, AuthenticationFilter, account predicates | HTTP valid/bad token, two-account access denial, deleted account denial | Passed; Identity deletion input is synthetic, not its producer E2E |
| Atomic diary/outbox and idempotency | NutritionService transaction/account lock | rollback, competing exact creates, changed-body conflict, stale child versions | Passed on PostgreSQL |
| Exact numbers at input, storage, event and client boundaries | Jackson BigDecimal, Snapshots, public decimal strings | micro/exponent/trailing zero inputs; over-max and 1e400 reject400; valid total9000000000000.001 survives JSONB, event, JetStream and generated KMP serialization | Passed; response number→string is an explicit breaking correction to unreleased API |
| Consistent HTTP errors and replay | Errors, expectedVersion, consumeSavedDish contract | missing header/query INVALID_INPUT400; zero/negative child DELETE version400; stale409; generated KMP client consumes first201/replay200 | Passed |
| Durable event correlation | controller→service→EventEnvelope | request ID persisted in outbox; replay neither changes correlation nor creates another event | Passed |
| Consumer-compatible water totals | transactional daily bound | Int.MAX_VALUE accepted; add/update/date move overflow400 rolls back row/version/outbox | Passed against actual Notification consumer |
| Verified TLS hostname | JNATS HappyEyeballs and verifying socket | trusted DNS/IP SAN connect/reconnect; wrong DNS with matching IP, missing IP and untrusted chain rejected | Passed on TLS NATS |
| Isolated outbox recovery | releaseSafely and leases | SQL release fault followed by healthy ACK/publish; lease reclaim; interrupt preserved even when release throws | PostgreSQL/JetStream plus focused interruption unit regression |
| Isolated deletion recovery | per-message recovery boundary | quarantine SQL failure followed by healthy deletion; TERM/NAK failures do not stop next message | Real DB/broker plus mock ACK-operation fault tests |
| Protected lifecycle records | runtime-grants.sql | actual restricted runtime cannot modify tombstones/read Flyway history; provisioning rejects unsafe PUBLIC table/column grants, schema CREATE, role membership, default grants and ownership | Passed on PostgreSQL; provisioning must run atomically as owner |
| Actual downstream ingestion | real Nutrition HTTP/outbox/JetStream and Notification bootJar process | meal create/update/delete; duplicate; fresh-ID stale snapshot after version3; water boundary and empty-day projection; inbox/projection assertions | Passed; no Notification production stub |
| Independent saved copies | consumeSavedDish | copy IDs differ; template edits preserve history; exact replay | Passed |
| Contract/build consistency | generated server/KMP transport, descriptors, CI selector | service checks, all active contracts, compileKmpPublicClient, shared convention/module selector regressions | Passed; integration task fingerprints Notification JAR and grants SQL |

Final local Nutrition run: 11 unit and 26 integration cases, no skips. Identity,
Notification, four platform checks, mobile shared tests and KMP compilation also
passed. Atlas generator freshness, Python/UI tests and CI scope mapping passed.
Existing generated KMP compiler warnings remain non-fatal.

## Regression and independent review evidence

The 12 PR comments on `7f2a7fe` were traced through producer, consumer,
provisioning and generated transport. Tests were run against an isolated source
copy of that revision with only regression tests/test dependencies overlaid.
They reproduced wrong TLS identity acceptance and valid DNS rejection, numeric
boundary/precision failures, malformed parameter errors, invalid child versions,
missing correlation, water overflow, recovery failures and ineffective grant
checks. The interrupted-release case was reproduced separately. CI selector
regressions also failed on the previous selector. All pass with the fixes.
The consumeSavedDish runtime already returned201/200 correctly; its test passes
before and after, and the correction is the missing200 OpenAPI response.

Two independent read-only reviewers covered numerical/HTTP/TLS/privilege
invariants and messaging recovery/contracts/test execution/CI selection. Their
additional findings led to explicit PUBLIC column/ownership checks, Gradle
integration input fingerprinting, broader CI dependency selection and a distinct
stale-version event test (fresh event ID, so inbox deduplication cannot mask it).
The ownership regression was also run before its SQL guard and failed.

Earlier implementation evidence against `2d9b18d` (3 unit/13 integration cases)
and rebase-only evidence against `7f2a7fe` are historical. They do not establish
these corrected guarantees or current-head CI. Earlier snapshot ordering and
out-of-range event_version fixes remain covered by the retained regressions.

## CI fixture follow-up

The first correction CI run on `9e5f3e9` passed Nutrition tests/image build but
exposed a PostgreSQL startup race in the existing Notification deployment test.
Its Unix-socket pg_isready probe could accept the temporary initdb server before
TCP was ready. A disposable container with delayed init SQL reproduced the old
probe succeeding while the subsequent TCP SQL failed. The fixture now waits
for SELECT1 through its actual authenticated TCP helper. This changes only the
test harness, not Notification production provisioning or runtime behavior.
The affected real-container boundary tests pass locally. The local Podman
Compose replacement cannot run the unrelated JSON Compose config assertion;
Docker Compose validation is therefore established by the final Linux CI run.

## Limits and explicit follow-ups

- Full handwritten Nutrition atlas flows/topology remain deferred by agreement.
  Only generated data.js/index.html are refreshed for main CI. Notification #20
  is merged, so the follow-up is now unblocked.
- Nutrition→Notification ingestion E2E is now implemented. Notification scheduling,
  provider delivery, authenticated Notification API/readiness, and full mobile
  application E2E are not established by that ingestion test.
- Recipe import and Media attachments await their real authenticated services.
- No production deployment, production credential/ACL rehearsal or merge.
- Fresh schema tests do not establish upgrades from an earlier deployed Nutrition
  schema; none exists. No other service migrations are changed.
- Runtime can mutate ordinary domain tables; this is service-level isolation,
  not per-user PostgreSQL RLS. Unsafe existing provisioning is rejected rather
  than silently repairing unrelated ownership/membership/default grants.
- ACK ambiguity uses broker acceptance followed by omitted DB completion and
  lease recovery; no host/process-kill outage rehearsal is claimed.
- Repository approval requirements remain separate from this independent agent
  review. CI success does not establish human approval or configure branch rules.
