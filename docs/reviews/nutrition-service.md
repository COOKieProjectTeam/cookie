# Nutrition implementation review evidence

Initial implementation/review base: `2d9b18dc754bf70e7a8ea627094ab7ba583f7847`.
Scope: standalone `feat/nutrition-service`; all implementation/report files in
this PR. No Notification implementation changes. Initial review conducted on the
working tree before commit; final PR head/CI evidence is recorded in its body.

## Invariants and executed evidence

| Guarantee | Implementation | Behavioral evidence | Result |
|---|---|---|---|
| Verified Identity subject and ownership | AccessTokens, AuthenticationFilter, NutritionService account predicates | HTTP valid/bad token, cross-account meal/child access, local deletion denial | Passed |
| Atomic diary + outbox | TransactionTemplate and per-account lock | rollback removes both records; concurrent exact creates produce one meal/two events | Passed on PostgreSQL |
| Idempotency and versions | client ID/hash rows, child/meal versions | changed body conflicts, stale item patch fails, deleted meal/water key conflicts | Passed |
| Snapshot calculation | Snapshots | decimal PIECE rounding, unknown macros, invalid units/values, date/DST validation | Passed |
| Independent saved copies | consumeSavedDish | copy IDs differ; template edits do not change history; exact consume replay | Passed |
| Full Notification snapshots | mealEvent/waterEvent/dayEvent | real outbox → JetStream messages, meal v1/v2/v3, both moved dates, empty day explicit null | Passed; contract compatibility only |
| Durable publication | NutritionOutbox | competing leases, stale completion rejected, ACK-before-DB-completion replay keeps broker message ID/sequence, failed independent publish does not block healthy record | Passed on PostgreSQL/JetStream |
| Durable deletion | AccountDeletion, DeletionConsumer | Identity-shaped envelope sent via real broker; inbox replay, erasure/tombstone, poison followed by healthy deletion, durable rejection + no pending ACK | Passed; synthetic Identity envelope, not Identity producer E2E |
| Runtime privileges | ops/runtime-grants.sql | real runtime role cannot UPDATE/DELETE tombstones or read Flyway history | Passed on fresh database |
| API/build model | generated DiaryApi, service.yaml, architecture model | all service checks, descriptor/bundle validation, KMP client compilation | Passed |

Nutrition has 3 unit and 13 integration tests, with no skips. Identity checks and
real integration tests, all four platform checks, existing notification-sink and
mobile shared checks also passed locally. CI scope mapping and pinned-image
checks passed. Existing generated KMP code emits non-fatal compiler warnings.

## Independent review and regressions

An independent read-only reviewer traced authorization, ownership, transaction,
idempotency, leases, deletion, generated transport, contracts, grants and CI.
Confirmed findings were reconciled with executable regressions:

- Recent foods sorted timestamp strings rather than instants. Before the fix,
  the same-second fractional regression returned amount 150 instead of 200;
  after sorting parsed instants it passes.
- Out-of-range event_version caused Jackson 3 to throw rather than return a
  controlled invalid-envelope result. The original reviewer initially inferred
  truncation/erasure, but runtime evidence disproved that inference: actual
  impact was retry instead of quarantine. The new range guard and broker test
  prove durable rejection without deleting that user's diary.
- Canonical Nutrition event definitions were updated to the full snapshot
  contract; no Notification implementation was copied.
- Publish ACL documentation now enumerates all five exact subjects. The initial
  one-token wildcard would not have matched those subjects; no production ACL
  was deployed from it.

## Limits and follow-ups

- Interactive HTML atlas update and Nutrition → Notification E2E are explicitly
  remain explicit follow-ups. Notification #20 is now merged; the rebase only
  refreshes the generated atlas snapshot required by main CI.
- Recipe import and Media attachments await their real authenticated services.
- No production deployment, production credential/ACL rehearsal or merge was
  performed. Image build is a CI check, not a production rollout.
- Tests cover a fresh Nutrition schema; there is no earlier deployed Nutrition
  schema to upgrade. Other services' existing data/migrations remain unchanged.
- Runtime role explicitly protects lifecycle tombstones and schema metadata;
  the compromised runtime role can still change ordinary domain tables. This
  is service-level access control, not per-user PostgreSQL RLS.
- ACK ambiguity is reproduced by broker acceptance followed by skipped database
  completion and lease recovery. A host/process kill was not performed. This
  is narrower than a full outage/deployment rehearsal.
- Ruleset inspection found PR approval requirements but no required status-check
  rule for main; this PR does not alter repository protection configuration.

## Rebase onto Notification-enabled main

Rebased onto `bf0ad8b` on 2026-10-21. CI jobs/aggregate checks and both event
contracts are preserved. Nutrition runtime behavior is unchanged. Generated
atlas data is refreshed to satisfy main's snapshot check; handwritten Nutrition
flows/topology and real Nutrition → Notification E2E remain outstanding.
Fresh verification and independent review are reported against the rebased head
in PR #21; the initial evidence above must not be treated as new-head CI evidence.
