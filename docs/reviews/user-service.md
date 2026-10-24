# User Service review evidence

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
