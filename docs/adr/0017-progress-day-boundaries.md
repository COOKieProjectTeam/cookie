# ADR 0017 — Progress day boundaries and achievements

Status: accepted for the Progress implementation. Initial implementation uses
the head of Health PR #45, `f83bc8414cc819234b870cc7676cf550c2bfcc1a`, as
the base for a dependent PR. Before retargeting to main, compare the merged
Health code and repeat the affected contract and process checks.

## Ownership and time

Nutrition owns food and water facts, User owns profile and accepted goals,
Health owns measurements and activity, Progress owns calendar and read
projections, and Notification owns reminder delivery. Progress uses local
PostgreSQL and durable events. Its only synchronous outbound dependency is
Identity signing-key discovery. A verified Mobile BFF may read today's
projection with a separately verified user context.

- Enrolment starts at the first committed User profile. An opened day fixes its
  local date, IANA zone, opening instant and next local midnight. Zone
  changes apply to the next opening. Repeated dates never earn twice, and dates
  completely skipped after a proven zone change preserve the running streak.
- A day can earn once with all four known macros in their inclusive ranges
  (or below a carb cap) and food present, or with an applicable cheat day.
  Activity and hydration do not participate. Confirmed absence of a calorie
  goal makes the streak inapplicable. An unconfirmed baseline is pending.
- The first complete goal can apply immediately within the open day,
  including to meals already present. Later accepted revisions take effect at
  eligible openings in source-revision order. An asynchronous Health
  assessment only becomes a goal once its result commits in User.
- Closing uses the snapshots committed strictly before the fixed boundary.
  A write started before midnight and committed after midnight changes the
  live facts only. The next opening may use changes committed exactly at the
  boundary. Historical facts and immutable closing results have separate
  storage and can diverge after correction.

## Owner commit evidence

Under its account lock, each owner writes scoped facts, a continuous
per-account sequence and the outbox in its domain transaction. A commit
group contains all relevant facts from that transaction. A move emits
both dates. Store the original event UUID and wire version for replay.

Read only a publication of INSERTs into the owner's progress commit-marker
table through a persistent `pgoutput` slot. Decode complete transactions
and persist their commit timestamp and end LSN before acknowledging the
position. Preserve a durable cursor across restart. A worker uses a dedicated
replication credential and owned SQL storage. The HTTP runtime has no
replication credential. Save durable evidence before emitting
`nutrition.progress_commit.recorded.v1` or `user.progress_commit.recorded.v1`.
Group evidence includes the account and exact contiguous sequence range.

Progress sends `progress.nutrition_cut.requested.v1` and
`progress.user_cut.requested.v1` after its fixed boundary. Under the same
account lock as mutations, the owner records a marker after the boundary,
including the latest sequence, and fences subsequent writes at that instant.
Decode its commit and require a continuous captured prefix through its
recorded sequence to prove all earlier account commits were processed.
The owner returns `nutrition.progress_cut.confirmed.v1` or
`user.progress_cut.confirmed.v1` with the requested instant and cursors
for commits strictly before and at or before that instant. An empty
prefix is valid evidence. Duplicate cut requests preserve the original
certificate. Replay requests retrieve bounded ranges of original events
and group evidence from the owner journal, even after broker eviction.

Source clocks must remain UTC-synchronised and nondecreasing across serialized
commits. Check the decoded stream for timestamp regressions and reject
promotion of the affected evidence. Enforce the last certified lower bound
under the account lock, fail closed when clock integrity is lost, and require
operator reconciliation before advancing across a discontinuity. Retain raw
timestamps, position and marker IDs for a faulted account before acknowledging
WAL, so unrelated accounts can keep progressing and the operator can reconcile.
Retain
the WAL cursor and monitor slot retention. Lost WAL cannot be replaced by a
new empty slot. See the PostgreSQL
[logical replication message format](https://www.postgresql.org/docs/18/protocol-logicalrep-message-formats.html).

Progress waits for complete groups through both cursors and the preceding
closing result before finalizing. Until then it exposes closing/pending state.
The unique account/date result, streak, and resulting outbox write commit
together under the account lock. Retrying any message or worker step must
have the same semantic effect as running it once.

## Isolation and verification

Consumption, closing and replay use the same account lock and deletion
guard. The Identity deletion request erases the account's owned projections,
source journal evidence and queued payloads and leaves a minimal tombstone.
See ADR 0011 for the broader account lifecycle.

Verify the protocol with PostgreSQL and JetStream using commits on both sides
of midnight, a transaction spanning midnight, rollback, reordered delivery,
crash before and after cursor persistence, and replay after broker eviction.
Verify domain rules separately with controllable instants and DST/travel
fixtures. Run the process path through Notification's scheduler and verify
that unknown inputs, mixed zones and cheat days suppress warnings. Review
all account locks, grants, workload policy and replay/deletion paths against
the final revision.
