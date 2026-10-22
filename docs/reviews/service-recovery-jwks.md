# Identity / Notification recovery and signing-key compatibility

Review base: `bf0ad8b270306c469df200effe929a694db8d860` (`main`).
Scope: the commit containing this report on `fix/service-recovery-jwks`;
final head and CI evidence are recorded in the PR to avoid a self-referential hash.
This branch contains no Nutrition implementation and is independent of PR #21.

## Invariants and evidence

| Invariant | Implementation | Behavioral evidence | Result / limit |
| --- | --- | --- | --- |
| Issuer configurations fit consumer signing-key limits | `KeyMaterial` and `RemoteJwksAccessTokenVerifier` share `IdentityAccessKeyPolicy` | `KeyMaterialConfigurationTest` rejects active/retiring kid129 and 17 total keys; accepts kid128 and 16 keys. `RemoteJwksAccessTokenVerifierTest` verifies tokens for all 16 keys and rejects excessive bounds. | Issuer regressions fail on base and pass after. Consumer limits already existed; this intentionally tightens startup configuration. |
| Failed outbox recovery cannot abort healthy independent records | `OutboxPublisher.releaseSafely` and `releaseAll` | `OutboxRecoveryTest`: publication or completion failure followed by failed release still completes the healthy record. `JdbcConcurrencyIntegrationTest`: real SQL trigger fails completion and release for one event, healthy event publishes, expired failed lease recovers with stable broker message ID. | Unit and real PostgreSQL/JetStream regressions fail on base and pass after. Existing claim fencing remains intact. |
| Failed quarantine/ACK/NAK cannot abort healthy fetched messages | `NotificationConsumer.consume` recovery boundary | `NotificationRecoveryTest` injects quarantine, quarantine ACK and delayed NAK failures before a healthy message. `NotificationRecoveryIntegrationTest` rejects quarantine INSERT in PostgreSQL: healthy deletion commits immediately, poison stays pending and is quarantined after fault removal. | Unit and real infrastructure regressions fail on base and pass after. The deletion envelope is synthetic; this test is not Identity-producer E2E. |
| Cancellation stops traversal without losing durable work | Explicit interrupt handling in publisher and consumer | Unit tests interrupt outbox submission / Notification handler, assert restored flag and untouched tail. | Passed. ACK-wait, connection-acquisition and recovery-operation interrupt paths also inspected, without dedicated new tests for each path. |
| CI fixture waits for its actual database connection | Authenticated TCP `SELECT 1`, replacing Unix-socket readiness | Four `ContainerBoundaryTest` cases exercise provisioning, repeat provisioning, runtime grants and Redis ACLs. | Passed. The same minimal harness correction was already reproduced with delayed init in PR #21; here the affected suite was rerun. |

Before/after unit execution: nine regression cases failed on untouched production
sources, then passed after fixes. In a separate `git archive` copy of base with
only the two new real-infrastructure tests, Identity failed with the injected
release SQL exception and Notification observed zero healthy tombstones instead
of one. Both pass with the fixes.

Full local validation: Identity **101 unit + 45 integration**, Notification
**100 unit + 73 integration**, zero failures or skipped cases; all four platform
modules, generated KMP client compilation and service descriptors passed. CI
scope mapping passed. Atlas generation/freshness, 14 generator and 29 UI tests
passed. Browser preview shows both key-bound facts, revised recovery behavior
and the source dialog highlighting the shared policy. No topology was changed.
The independent PR has no Nutrition atlas content; its separate deferral remains.

Two read-only reviewers checked recovery/durability/cancellation and the full
implementation/contracts/test scope; one started with fresh context and raw
requirements. No confirmed production defect remained. They inspected code and
tests rather than running containers. Final-head follow-up is recorded in the PR.

## Compatibility and verification boundaries

- Before upgrade, correct signing key rings exceeding 16 total keys or kid128;
  preserve the existing retiring-key overlap required by token lifetime/cache.
  Token shape, crypto algorithms, JWKS refresh and HTTP cache behavior do not change.
- Fault-injection integration tests use database-owner credentials to install
  triggers; they do not prove runtime permissions. Existing role-boundary tests
  run separately; no migrations, grants or service ownership changed.
- ACK ambiguity remains at-least-once, not exactly-once. Stable IDs, leases and
  inbox deduplication remain required; a recovery metric is not delivery proof.
- Production was not deployed. Local checks use macOS/Podman; Linux deployment
  bundle/rehearsal validation belongs to CI. No new native Windows job was added.
- Effective `main` rules inspected on 2026-10-23 require PR review and resolution
  but expose no required-status-check rule. A green `CI required` workflow job is
  therefore not claimed as enforced branch protection. Repository settings were
  not changed.
