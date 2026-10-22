# ADR 0014 — Autonomous Nutrition service

Status: accepted for this implementation scope, 2026-10-21.

## Context

Nutrition must merge independently onto main. At the initial implementation,
Notification PR #20 was unmerged;
its service, deployment expansion, scaffold and HTML atlas infrastructure must
not be imported. Recipe and Media do not yet have implemented callees. The user
approved autonomous diary/saved-dish/water scope and deferred those integrations.

## Decisions

Use main's Kotlin/JVM convention plugins, generated OpenAPI transport, bounded
request filter, EventEnvelope and Identity's verified NATS connection pattern.
Do not introduce a Notification stub or shared domain package. A public-only
mounted JWKS file is the minimum independent Identity verification adapter;
PR #20's remote bounded JWKS loader is not a dependency. Operators atomically
replace that file with Identity's current and retiring public keys during
rotation. No Identity private key belongs in Nutrition. Missing/invalid key
material returns 503 and fails readiness; invalid tokens return 401.

Persist a meal as one owned JSONB aggregate with immutable child IDs, editable
food provenance/snapshots, decimal-derived totals and a meal version. This
replaces the earlier normalized target DBML. Saved templates and water entries
are separate aggregates. Each mutation obtains a per-account PostgreSQL
transaction advisory lock and predicates all reads/writes on that account.
Hash collisions only serialize otherwise independent accounts. Transactions
have a five-second bound and never perform network calls. JSON arrays are
bounded to 50 dishes and 100 items per dish; HTTP bodies are capped at 256 KiB.

An explicit IANA timezone is required on meal/water creation and saved-dish
consumption. It stays fixed for that record. The supplied localDate must match
its instant in that zone. A move changes localDate and timestamp together and
publishes both days. This refines the previously inactive roadmap contract;
there are no deployed Nutrition clients to migrate. Water updates/deletes now
also require an optimistic version. Source remains provenance; Nutrition does
not call Food Catalog or claim to verify externally supplied nutrient values.

Meal and water creation require an account-scoped clientGeneratedId and a
canonical request hash. Exact retries return current live state; changed bodies
or deleted IDs conflict. Deleted rows retain only key/hash/date/version metadata
until account deletion. Saved consumption hashes the template ID plus request,
then checks replay before loading the mutable template. It creates independent
item IDs. Saved deletion is physical because templates have no event consumer
or creation idempotency key; existing consumed meals are unaffected.

Commit writes and full event snapshots to outbox atomically. Meal events contain
only mealId/userId/eatenAt (deleted omits eatenAt), with a positive monotonically
increasing meal aggregate_version; no dish details enter reminder events.
Water snapshots contain userId/localDate/totalMl/lastConsumedAt, including an
explicit null timestamp when empty. Separate per-account/day counters survive
empty days. These Nutrition-only changes replace the old planned delta shapes
and implement Notification #20's published requirements. Day snapshots include
nutritionTotals for future Progress consumers. Unknown macros remain null.

Outbox claims use SKIP LOCKED and token-fenced 30-second leases. Async broker
publishes have a shared ten-second acknowledgement deadline and stable
Nats-Msg-Id. Only acknowledged records become published; ambiguous completion
can redeliver and requires downstream deduplication/version checks. Retry grows
from one second to five minutes with jitter. Published rows expire after seven
days. Account deletion removes pending diary event bodies as well as live data;
an already claimed/published message cannot be recalled, so consumer lifecycle
tombstones remain required. Minimal deletion/inbox/rejection metadata is retained.

Consume only Identity account.deletion.requested using a durable pull consumer.
Inbox uniqueness, append-only lifecycle tombstone and local erasure commit before
ACK. Invalid envelopes persist only stream sequence/reason before TERM; replay
needs separately authorized operator access to the retained original. This does
not implement a cross-service deletion-completion protocol: Identity remains
DELETION_PENDING until that separately designed protocol exists.

## Explicit deferrals and evidence boundary

- Complete handwritten Nutrition atlas flows/facts and topology. Notification
  #20 has now merged; the rebase refreshes only the generated contract snapshot
  required by main CI. The agreed full-map readiness exception remains.
- Nutrition → Notification ingestion now has an actual-process E2E test: HTTP,
  PostgreSQL/outbox, JetStream and Notification inbox/projections. Scheduling,
  provider delivery and Identity HTTP-driven lifecycle remain separate evidence.
- Recipe import and Media attachments require real authenticated callees and
  generated per-callee clients. They remain roadmap operations, not active API.
- Production configuration/rollout and automatic merge are outside this request.

## Consequences

JSONB aggregates keep one clear transaction/version boundary and avoid mutable
cross-service references. Large accounts can make recent-food scans/day totals
costly; the database timeout bounds work but an indexed read projection may be
needed after measurement. The account lock serializes that account's diary
writes, including deletion; unrelated accounts proceed independently. JWTs
remain usable until expiry or a consumed local deletion tombstone; broker lag
therefore delays local revocation, as in the existing lifecycle design.

## Review corrections, 2026-10-22

Parse both request JSON and persisted JSONB as BigDecimal before validation or
hashing; normalize trailing zeros for the six-fractional-place input policy.
Keep exact numeric event payloads and persisted snapshots. The public Nutrition
response uses decimal strings (nullable for unknown macros), because valid large
aggregates cannot retain millesimal precision through generated Double clients.
This is an explicit breaking correction to the unreleased API, with generated
server and KMP transport changed together. Inputs remain bounded decimal numbers;
clients must convert response strings explicitly when constructing edits.

Cap each user's local-day water total at 2147483647 ml, matching the existing
Notification v1 integer validator. Check every affected day inside the same
transaction; exceeding the cap returns INVALID_INPUT and leaves documents,
versions and outbox unchanged. This avoids changing another service's contract.

Pass normalized request IDs from the controller to event creation explicitly;
publishers do not inspect servlet state. Background operations without a request
may leave correlation null; incoming deletion does not generate events. Exact
retries retain original event identity/correlation. Reject missing HTTP arguments
and nonpositive versions with the public INVALID_INPUT envelope. Document 200
for saved-template replay alongside 201 for first consumption.

Preserve broker DNS identity using HappyEyeballs plus the TLS data-port guard,
as in current main. A trusted certificate with the wrong DNS SAN is rejected even
if an IP SAN matches the resolved address. Contain release/quarantine/TERM/NAK
failures per item, preserve unacknowledged durable work and restore interruption.
Provisioning requires an unprivileged non-owner role; reject unsafe effective
schema/table/column privileges and inherited/default grants instead of assuming
that a direct REVOKE proves isolation. No production grants are changed here.
