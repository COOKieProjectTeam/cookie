---
name: service-review
description: Review COOKie service changes against behavioral invariants and record verification evidence. Use for new services, changes to delivery, ownership, persistence or provider guarantees, and service PR reviews.
---

# Service review

Commands and code paths below are relative to the repository root. Follow
`AGENTS.md` and applicable nested instructions. This skill defines review
evidence; it does not authorize fixes, merges, deployments or repository setting
changes beyond the user's request. For a review-only request, inspect and report
without modifying the repository.

## Establish the guarantees

Before implementing a new service, record its important invariants in the
service README or a linked architecture document. A small list, often 5–10
items, is useful; the number is not a completion criterion. For an existing
service, reuse and update the maintained list. During a read-only review,
derive missing invariants in the report instead of writing files.

Read relevant accepted ADRs, architecture models, active contracts and the
user's requirements. Separate required behavior, current implementation and
unresolved decisions. Write invariants as observable guarantees, with the
identity/namespace, transaction boundary and failure outcome where relevant.
“Uses inbox” is not a guarantee; “redelivery after a committed event creates no
second job” is.

Revisit assumptions whenever a provider, namespace, ownership rule or delivery
policy changes. Follow each changed assumption through API, data model, unique
constraints, locks, delivery, retries, migration, permissions and documentation.
An unchanged file may implement an assumption invalidated by the new design.

## Select evidence by risk

Map each critical invariant to a named behavioral test and its assertion.
Use the applicable rows below; explain meaningful omissions. Do not add dummy
tests or require infrastructure irrelevant to the service.

| Changed guarantee | Evidence to obtain |
| --- | --- |
| Authentication and ownership | Two distinct users; verified identity rather than caller-supplied ownership; denied cross-user reads/writes; relevant namespaces and concurrent transfer/registration. Lock keys, unique indexes and provider identifiers must describe the same ownership scope. |
| Failure isolation and retries | A failing item followed by a healthy independent item. Distinguish per-item, per-key, per-tenant and provider-wide failures; inspect shared circuit breakers, queue progress, timeouts and retry budgets. |
| Persistence and privileges | Fresh database and upgrade from the previous schema with representative data. Connect as the actual runtime role and attempt forbidden operations, including protected metadata/tombstones where present; inspect existing grants and default grants. Superuser-only tests do not prove role isolation. |
| Events and workers | Duplicate and out-of-order events, rollback before ACK, crash/restart around commit, competing workers, version checks and deletion tombstones where relevant. Use real PostgreSQL/broker semantics when mocks cannot reproduce them. |
| External side effects | Explicit boundaries before submission, ambiguous response and confirmed acceptance; retry/permanent-error behavior and provider-specific headers. Assert forbidden repeats, not only eventual success. |
| Scheduling and ephemeral indexes | Deterministic clock, relevant timezone/DST boundaries, recovery after downtime and cache loss; verify authoritative state and duplicate prevention. |
| Deployment and configuration | Fresh install and existing volumes, runtime credentials, independent component update, interrupted deployment and rollback. Use disposable environments; distinguish real container checks from mocked lifecycle tests. |
| Shared tooling portability | Exercise supported operating systems where filesystem or shell semantics differ. URI/contract paths and host paths have different rules. Linux success does not establish Windows support; a simulation is narrower evidence than a native runner. |
| Contracts and architecture | API/producer/consumer compatibility, generated client compilation, descriptor/model consistency, current atlas facts and flows. Use the repository architecture-map skill when represented behavior changes. |

For a confirmed behavioral defect being fixed, add a regression and demonstrate
failure on the defective revision and success on the fixed revision using
isolated worktrees or temporary copies, preserving unrelated local changes.
If reproduction is blocked, state the exact limitation and do not claim this
proof exists. Reuse tests/helpers when they exercise the guarantee; introduce
shared fixtures only after two real consumers need them.

## Review the whole affected flow

Record the base and head revisions and any uncommitted scope before reviewing.
For a current-state review without a comparison range, record HEAD and state
that no base was supplied; do not invent a PR range.
Trace representative input through authorization, application logic, database
constraints/transaction, event or job processing and external side effects.
Inspect unchanged participants of that flow, including provisioning scripts
and default configuration. Scope by the guarantee, not only changed lines.

For a new service or a substantial change to these guarantees, use independent
reviewers when available. Cover behavior, security/privacy, reliability and
contracts/tests; divide work by actual risks rather than a fixed agent count.
Give reviewers the same raw requirements, base/head, file scope and relevant
contracts. At least one reviewer should start with fresh context without the
author's conclusions or claim of readiness. Existing review-swarm tooling may
help, but this workflow must also work without a personal skill installation.
If independent review is unavailable, state that limitation.

Ask each reviewer for:

- Confirmed findings: severity, precise location, trigger, violated guarantee,
  observable impact, supporting evidence and a focused fix direction.
- Checked guarantees and the concrete code/tests/commands inspected.
- Relevant areas not checked, assumptions and missing verification.

Reconcile conflicting findings against code or a reproducer. A missing test is
a coverage gap, not proof of a production bug. “No findings” without coverage
evidence is not proof of readiness. Reviewer count and test count are not
quality criteria.

## Report and integrate

For an implementation PR, keep maintained invariants with the service; put
revision-specific evidence in the PR description or review report:

| Invariant | Implementation path | Test/assertion or command | Result and revision | Unverified area |
| --- | --- | --- | --- | --- |

Fill the table with actual evidence; do not leave illustrative passing rows.
Include unresolved findings, failed/skipped checks and applicability decisions.
After fixes or a rebase, rerun affected checks and review affected guarantees.
Tie the final review and CI run to the current PR head; earlier evidence must
be identified as such, with any unchanged inputs explained.

When adding a service, register its real checks in
`.github/scripts/detect-ci-scope.sh` and the workflow, and verify that common
changes select all affected services. A declared integration task without
behavioral tests is not coverage. See `.github/README.md` for the aggregate
`CI required` check. A workflow definition does not make it a required GitHub
merge check: inspect the effective ruleset when claiming merge enforcement.
Report missing OS jobs or ruleset enforcement separately; documentation does
not implement them, and changing repository settings requires task authority.
