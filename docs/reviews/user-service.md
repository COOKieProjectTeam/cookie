# User Service review evidence

## 2026-10-25: PR review corrections

Base/HEAD: `2117b1d62c98cd4175265e8bd6b387551da4e4e2`; evidence below
applies to the uncommitted working-tree fixes on that base. Earlier sections
describe historical revisions and are not evidence for this change.

All ten review topics were addressed: profile and event counters are separated;
goal and hydration histories have independent revisions; existing-profile reads
share a lock and never execute bootstrap INSERTs; timezone lookup no longer
loads diet/allergies; the unreachable activation error and four unused planned
schemas are removed; JWKS policy and NATS connection/consumer mechanics have two
real platform consumers; CI fan-out comes from validated service descriptors.
Profile.version remains the public wire field; the internal column is now
profile_version. V003 preserves existing profile versions and seeds counters
from retained revisions/outbox rather than restarting sequences.

| Invariant | Evidence in this working tree | Result | Unverified area |
| --- | --- | --- | --- |
| Subresource commands do not invalidate profile patches; histories and events remain ordered | `subresource writes preserve profile version and independent goal revisions` | Real PostgreSQL pass; same test fails with VERSION_CONFLICT on the base | Production rollout/rollback not performed; migrate before starting upgraded User |
| Concurrent reads remain compatible with deletion and bootstrap | `existing account reads share locks while deletion waits for readers`; `parallel first reads bootstrap one profile without lock upgrade deadlock`; existing lifecycle tests | Pass; old exclusive read times out in the first regression | Load/latency benchmark not run |
| Goal reads only require timezone, not diet/allergies | `goal and hydration reads need timezone but not diet or allergies` revokes SELECT on both irrelevant tables | Pass; base fails with permission denied on diet_preferences | No production query tracing |
| Upgrade preserves counters and restrictive grants | `isolated migration upgrades existing counters and preserves restricted runtime grants`, starting at V002 with profile version8 and history7/6, using the migration JAR and user_app | Pass; next revisions8/7, profile9, event11; immutable/identity grants remain restricted | Large-volume migration and deployment rollback not exercised |
| Backfill scans retained histories once, including accounts without goal history | `counter backfill scans retained outbox once for many profiles` runs production backfill SQL through PostgreSQL EXPLAIN ANALYZE for64profiles/512events; upgrade fixture also preserves outbox10 above profile3 and next hydration1/event11 | Pass: outbox scan loops1; pre-fix correlated SQL fails with64loops | Execution plan tested on this fixture; no production-sized timing claim |
| JWKS refresh contenders do not wait on network; expired tokens fail closed | IdentityTokensTest slow discovery/expiry regressions and platform verifier tests | Pass; both service regressions fail on base (monitor timeout / accepted expired JWT) | Real Identity key rotation and outage load not exercised |
| Message/quarantine/ACK/NAK failures do not abort healthy batch items | Platform JetStreamPullConsumerTest, NotificationConsumerTest quarantine outage; User runtime replaced-connection and broker outage tests | Pass; base consumer lets the quarantine exception escape | Provider delivery and broker production ACL not newly deployed |
| Shared connection validation preserves TLS/replay policy | Platform NatsConnectionSupportTest plus Notification TLS peer and replay suites | Pass | Production credentials/truststores not accessed |
| Runtime and contract agree; planned components are reachable | UserContractTest, all active/bundled OpenAPI validation, descriptor validation and transitive planned-schema check; generated KMP JVM tests | Pass | Planned Health onboarding remains unimplemented |
| Shared changes select every real CI consumer | Full test-detect-ci-scope.sh and 11 descriptor selector tests including omitted Gradle dependency failure | Pass | Effective GitHub ruleset not changed/rechecked |
| Atlas facts reflect the changed code | architecture-build/check, User HTTP filter → PATCH → flow/map and source inspection in local browser | 14 generator tests and 70 JS/UI tests pass | Atlas represents source, not deployed production |

Verification: User, Notification, Identity, Nutrition and platform checks;
all four service integration suites on local Podman; generated public client
and mobile JVM tests. Counted main-service/platform/mobile XML reports contain
403 tests, zero failures/errors/skips. Domain/application checks also passed or
were up to date. Integration startup first failed because Testcontainers looked
for /var/run/docker.sock; the successful runs explicitly used Podman's local API
socket with its rootless-compatible Ryuk setting. No test gates were disabled.

Six new behavioral regressions were independently run against an isolated source
copy of the base with only test deltas: all six failed for their intended reason,
then passed in the fixed tree. The independent review-agent pass reported no
actionable findings after inspecting the full diff and untracked additions.
The four-role review-swarm covered regression, security/privacy,
performance/reliability and contracts/coverage. It identified one P2 in the new
V003 backfill: an unindexed correlated outbox maximum was scanned once per
profile while the migration held its lock. The correction groups each retained
history once and joins maxima by user_id. A seventh fail-before/pass-after
regression verifies the production SQL execution plan (64 scans before, one
after). The complete User integration suite passed again after this fix.
The performance reviewer and review-agent both re-reviewed the migration and
its additional tests with no remaining findings. The other three swarm roles
reported no additional actionable defects. Reviewers inspected source/tests;
the main agent ran the successful suites. No production rollout, remote commit,
push or GitHub ruleset change was performed.

## 2026-10-24: P3 contract correction and current main integration

This follow-up starts from PR head `e4f3578bc92fc63823a878f3c79377524e882344`
and merges main `3200797` (the completed Nutrition service). Historical evidence
below applies to its named revisions; the PR description records the new exact
head and its CI result separately.

The active User schemas now expose the existing runtime limits: locale ru/en,
height 30–300 cm, target weight 1–999.99 kg, weekly change -99.99–99.99 kg,
and exact 0.01 increments. IANA timezone semantics are documented. Accepted
wire values are unchanged; regenerated Kotlin locale properties are now enums,
and the handwritten transport maps them to/from domain strings.

| Invariant | Implementation path | Evidence | Follow-up result | Unverified area |
| --- | --- | --- | --- | --- |
| Contract acceptance matches domain validation | User OpenAPI and UserContractTest | Locale, height and both weight fields: accepted/rejected boundaries and trailing zeros, for request and response schemas | All 3 new tests fail on the original contract and pass after correction | The focused evaluator covers enum/minimum/maximum/multipleOf, not every JSON Schema keyword |
| Generated callers and transport compile together | User ProfileController and generated KMP client | User/Nutrition/Identity/Notification checks; compileKmpPublicClient and OpenAPI/descriptor validation | 343 backend JVM tests plus 1 generated-client HTTP test pass on native Windows, no failures/skips; generation and compilation pass | Container tests require CI because local Docker daemon is unavailable |
| Generation configuration uses portable contract paths | build.gradle.kts contractPath | validateOpenApiGenerationConfig rejected Windows backslashes before normalization and passes afterward | Native Windows pass; no validation gate disabled | Linux result belongs to the new CI run |
| Both service checks survive the merge | CI selector/workflow/aggregate | test-detect-ci-scope.sh covers User, Nutrition and their shared inputs | Pass under Git Bash | Effective GitHub ruleset not changed or re-verified |
| Atlas preserves both services and current contracts | Explorer sources and generated snapshots | 14 generator tests, 70 UI tests, snapshot check; browser User schema and HTTP → flow/map inspection | Pass | No production-topology claim |

User security is explicit on every operation and uses the shared accessToken
scheme. A separate BearerAuth scheme made the generated setBearerToken() helper
configure a different authenticator, so User requests omitted Authorization.
GeneratedUserClientTest reproduces the missing header before the correction and
checks a real local HTTP request afterward; the public-client CI job runs it.
CI jobs, source filters, model facts and atlas scenarios retain both services.
An independent reviewer checked the combined service integration; the NATS
inspector now includes User among implemented producers and lifecycle consumers.
Full Identity E2E, production TLS/NATS ACL and publisher crash/lease fault injection
remain the limitations recorded below. No deployment or main-branch merge occurred.

PR #22 was inspected at `0f68e0ba87d250b403c3afac6b22b55f8a07b5f5` before
updating this branch. Its Identity/Notification recovery and JWKS changes remain
separate; those runtime sources and the Identity contract were not changed by
this follow-up. Shared atlas descriptions preserve both User and Nutrition.

## Original implementation evidence

Base: `bf0ad8b270306c469df200effe929a694db8d860` (main). Initial implementation:
`b29df891a61049bf5409bb6a91e1bde34ca36124`. Reviewed behavioral fixes:
`d6c1b7c5a8b13c57a468d2b427487131b5d2f87d`. This report and subsequent atlas-only
producer wording do not change the tested service inputs. The PR records its
current head and CI run separately.

Maintained invariants: [User README](../../backend/services/user/README.md).
Two independent, read-only reviewers covered persistence/lifecycle/reliability
and contracts/auth/configuration/CI. Both began with fresh context. They reviewed
raw requirements and base/head, including unchanged Identity and Notification
participants. Both re-reviewed d6c1b7c with no remaining concrete findings in the
affected scope. They inspected author-run logs/XML; they did not independently
execute tests.

| Invariant | Implementation path | Behavioral evidence | Result/revision | Unverified area |
| --- | --- | --- | --- | --- |
| Verified account ownership | IdentityTokens, UserController | IdentityTokensTest; two signed accounts in `verified users are isolated strict HTTP contract and real event publication` | 5 unit + 23 User integration tests pass at d6c1b7c, none skipped | JWKS and Identity producer are fixtures; not full Identity E2E |
| Lifecycle wins over stale requests | LifecycleEvents, JdbcUserStore account lock | Concurrent/duplicate activation, deletion before activation and concurrent writes; broker deletion then stale-token 403 and processed late activation still 403 | d6c1b7c pass | No deployment restart/fault injection at every ACK boundary |
| Atomic inbox and outbox | LifecycleEvents, JdbcUserStore | `rollback before inbox commit leaves event replayable`; version conflict preserves state/outbox; specific inbox event required before HTTP activation read | d6c1b7c pass | Crash after broker acceptance/before outbox bookkeeping not process-killed |
| Durable independent delivery | UserConsumer, UserPublisher | Invalid event quarantined then healthy event inbox committed; replaced connection recovers; real broker paused while HTTP commits, then pending outbox drains | d6c1b7c pass | Competing publisher lease expiry/fencing inspected, not directly exercised |
| Optimistic writes and command retries | JdbcUserStore | Concurrent version writes have one winner; concurrent same-key commands produce one result/event; changed command conflicts; equivalent decimal representations replay | d6c1b7c pass | JSON array order remains significant |
| Immutable history and account-local goals | UserDomain, JdbcUserStore | History/account isolation, same-day revision tie-break, timezone boundary, competing monthly cheat-day writes and delete/replacement | d6c1b7c pass | Automatic onboarding deferred to Health |
| Actual runtime privileges | V001/V002, UserPersistenceIntegrationTest | user_app cannot mutate tombstones/payload/migration/schema, delete immutable children, cascade-delete active profile or reassign profile identity; lifecycle erasure succeeds | d6c1b7c pass | Dedicated production role provisioning remains operator responsibility |
| Upgrade and grants convergence | UserMigrationIntegrationTest | Actual migration JAR upgrades V1 with representative data and pre-existing broad grants; new tables do not inherit runtime access | d6c1b7c pass | Production volumes/rollback not deployed |
| Notification compatibility | UserRuntimeIntegrationTest launches actual Notification JAR on separate DB | Real User event through JetStream updates default locale; explicit Notification locale/timezone survive later profile version | d6c1b7c pass | SMTP/FCM delivery outside this change |
| Contracts and generated transport | active User contract, descriptors, models | validatePlannedOpenApi, validateServiceDescriptors, bundled OpenAPI validation, compileKmpPublicClient; CI scope mapping | b29df89 pass; unchanged by d6c1b7c except domain normalization | Health assessment/weight/workload policy remain planned, not generated clients |
| Shared adapter compatibility | starter-web/messaging extracted adapters | Platform checks; Identity 95 unit + 44 integration; Notification 94 unit + 72 integration | b29df89 pass; shared inputs unchanged by d6c1b7c | Production broker TLS/ACL not exercised by User dev-broker test |
| Atlas accuracy | source details, app.js, generated snapshots | architecture-build/check; 43 UI tests; browser HTTP→flow/map, event producer filter, source viewer | Service snapshot regenerated after d6c1b7c | Atlas is source evidence, not production topology proof |

## Fail-before / pass-after evidence

- Direct runtime deletion of immutable goal/inbox/idempotency data: actual-role
  regression failed before the V002 restriction and passed after it (b29df89).
- Old Identity accounts stranded after activation retention: HTTP regression failed
  before verified-token bootstrap and passed after it (b29df89).
- Parent-profile CASCADE bypass and profile identity reassignment: both added
  actual-role tests failed on b29df89, passed on d6c1b7c.
- Valid redundant decimal zeros and equivalent-value retry: both added HTTP tests
  failed on b29df89 (400/409 respectively), passed on d6c1b7c.
- User HTTP detail inherited Notification facts/production route: UI regression
  failed before operation-specific facts/route, passed in b29df89.
- User event detail incorrectly named Identity as producer: UI regression failed
  before dynamic producer rendering; all 43 tests pass after it.
- Oversized lifecycle event version was initially reported as a bug, but the
  before test passed: Jackson 3 already rejects overflow. Reviewer withdrew it;
  explicit range validation is defensive clarification, not a reproduced fix.

Local logs are under ignored `.cache/user-*.log`; durable assertions live in the
committed test suites. Initial test authoring also exposed missing Jackson Kotlin
module and scale-sensitive test equality, fixed before the final suite passed.

## Other checks and boundaries

Go test/vet, Terraform formatting, compose config and CI scope tests pass locally.
Local Terraform validation cannot run with installed Terraform 1.5.7 because the
repository requires `~> 1.16.0`; CI uses the pinned supported version. No version
constraint was weakened. No production deploy, ingress, database or broker
permission change was performed. User ships as a buildable Spring JAR with an
isolated migration mode; container/rollout wiring is not activated.

Effective main rules were inspected through GitHub: review/last-push approval and
stale-review dismissal rules exist, but no `required_status_checks` rule was
returned. The workflow aggregate `CI required` therefore does not prove server
merge enforcement. Repository settings were not changed.

The user's decision is explicit: Health owns the initial nutrition/hydration
calculation. The roadmap now declares its assessment endpoint and idempotent
initial-weight dependency. User implements manual profile/goals today and leaves
`completeOnboarding` planned, with `onboardingCompleted=false`; no formula or
success stub is shipped. Nutrition PR21 and recovery/JWKS PR22 were still open
when checked; their unmerged code is not included in this branch.
