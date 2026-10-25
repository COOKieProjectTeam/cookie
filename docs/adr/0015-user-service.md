# ADR 0015: User ownership, manual goals and Health-owned calculation

- Status: accepted
- Date: 2026-10-23

User owns profile, diet/allergies, accepted immutable nutrition/hydration goal
versions and daily overrides. Health owns measurements **and the calculation of
suggested nutrition/hydration targets**. This ownership was explicitly selected
while implementing User; no formula is inferred from the old onboarding roadmap.

The independent User release implements profile reads/optimistic patches, manual
goals/history, hydration goals and daily overrides. `completeOnboarding` remains
planned until Health implements `calculateNutritionAssessment`, idempotent manual
weight recording and callee-owned workload authorization. Its public planned
shape is recorded now; it is not an active service contract or a code-generation
input. The future User→Health edge does not grant current runtime access. No
Health client or fake successful onboarding is shipped; `onboardingCompleted`
remains false for these initial profiles. Sex/birth date remain unset until that
flow is implemented. User never writes Health tables or invents a measurement.

Identity access JWTs are verified locally (ES256, `typ=at+jwt`, issuer/audience,
iat/exp and UUIDv7 sub/sid/jti). JWKS comes from one configured bounded TLS origin;
HTTP is allowed only in dev/test. Discovery permits at most 16 keys from main's
existing consumer policy. PR #22's common key-ring policy is an independent change;
User does not import that branch. No domain HTTP client is required for public
key discovery. Bounded HTTP exchange and hostname-verifying NATS socket adapters
are platform utilities used by both Notification and User. Both services also
use the same Identity verifier in starter-web: a process-local 300-second key
cache, scheduled refresh every 240 seconds and minimum 30-second refresh interval.
Atomic refresh ownership permits one network fetch without making concurrent
request threads wait on its monitor. Still-valid cached keys remain usable;
requests without a usable required key fail promptly with 503 while another
refresh is active. Cache and token expiry are checked after discovery returns.

Activation and the first authenticated access can initialize an incomplete
profile. Lazy initialization handles existing accounts whose activation expired
from the seven-day broker stream. It relies on an unexpired Identity token, never
an unverified account ID. Defaults are explicit UTC/ru, not inferred from IP.
Deletion, bootstrap and mutations use the exclusive transaction-scoped account
advisory lock. Existing-profile reads use its shared form, so they can run
concurrently while still excluding deletion and mutation. A missing-profile read
releases the shared lock before exclusive bootstrap and rechecks the tombstone;
it never upgrades while holding a shared lock. Append-only tombstones prohibit
both bootstrap paths after local deletion. Immediate bootstrap makes
`PROFILE_NOT_READY` unreachable; the public contract no longer advertises it.
As in Notification, previously issued JWTs can remain usable until the deletion
event is consumed. Identity alone owns sessions and final deletion state; no
completion/acknowledgement protocol or `account.deleted` is introduced.

Every mutating public operation requires UUIDv4 Idempotency-Key scoped to account
and operation (including override date). Canonical domain input is fingerprinted,
and the response is committed in the same transaction. Evidence remains until
account deletion. Profile changes compare the supplied `Profile.version` under
the account lock; only profile updates increment its `profile_version` column.
Every emitted User event advances a separate account-wide `aggregate_version`.
Nutrition goals and hydration goals each have an independent revision counter;
neither goal nor override mutations invalidate the client's profile version.
Migration V003 preserves existing profile versions and revision rows and seeds
each new counter above its existing history. Current
manual goals use the account-local current date; newest revision breaks date
ties. Historical revisions cannot be updated. A generated nullable cheat_month
and unique `(user_id,cheat_month)` enforce the monthly cheat-day rule in SQL.

User is both publisher and consumer. Inbox/lifecycle/state/outbox commit once;
ACK occurs afterwards. Outbox claims one event at a time with SKIP LOCKED,
30-second fencing leases and a ten-second broker deadline, within a bounded
poll budget. Retry ranges from one second to five minutes with jitter. A failed
release/ACK/quarantine operation cannot abort an independent item in the batch.
User and Notification share NATS TLS/file validation, connection lifecycle and
the pull-consumer runner in starter-messaging. Service adapters own subjects,
durables, handlers, quarantine formats and metrics. Inbox retry uses equal jitter
between half and all of `min(2^min(deliveredCount,9),300)` seconds; retry remains
unlimited for transient failures. Replaced connections rebind subscriptions.
Quarantine stores hash/subject/reason/sequence only. Runtime may not fetch arbitrary
shared-stream messages. Recovery outside retention needs separately authorized
operator evidence, not reads of another service's database.

Runtime grants are explicit: no schema/migration writes, append-only tombstones,
no UPDATE/DELETE on revision/idempotency/inbox evidence, and only delivery-column
updates on outbox. RLS allows outbox erasure only after a local deletion tombstone.
Profile deletion cascades through owned data. Inbox and quarantine are retained
conservatively; bounded operator maintenance can remove evidence older than the
stream redelivery horizon. No runtime cleanup weakens deduplication guarantees.

The service runs independently as a JVM artifact with separate migration mode.
Production ingress, credentials and deployment are not activated in this PR.
Readiness covers PostgreSQL and usable JWKS; NATS outages leave HTTP available
and expose durable outbox backlog/failure metrics. Health integration and full
Identity→User→Health E2E remain dependencies, not fixture-based success claims.
