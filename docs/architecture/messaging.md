# Event delivery protocol

## Applicability

Transactional outbox обязателен для сервиса, который публикует durable events.
Idempotent inbox обязателен для сервиса, который потребляет events. Эти роли
независимы: сервис может иметь обе, одну или ни одной. Identity v1 имеет
`publishes`, но `consumes: []`, поэтому использует только outbox.

## Guarantee

NATS JetStream delivery is **at least once**. Exactly-once business effects are
achieved by the consumer's PostgreSQL inbox and idempotent domain operations,
not by assuming that the broker publishes exactly once.

## Event envelope

Every event contains at least:

```yaml
event_id: uuid
event_type: nutrition.day.changed
event_version: 1
occurred_at: RFC-3339 UTC timestamp
producer: nutrition
aggregate_type: day
aggregate_id: string
aggregate_version: integer-or-null
correlation_id: uuid-or-null
causation_id: uuid-or-null
trace_id: string-or-null
payload: object
```

Business payloads are listed in `model/events.yaml`. Consumers must ignore
unknown additive fields. Breaking payload changes require a new
`event_version` and an explicit migration plan.

## Producer transaction

1. Begin PostgreSQL transaction.
2. Validate command and mutate service-owned domain state.
3. Insert one immutable `outbox_events` row for every emitted event.
4. Commit once.
5. Publisher worker claims pending rows safely across replicas.
6. Publish to JetStream with `event_id` as the broker message/deduplication id.
7. Mark the row published only after broker acknowledgement.

A crash between steps 6 and 7 can publish a duplicate and is expected.

## Consumer transaction

1. Receive a JetStream message.
2. Begin PostgreSQL transaction.
3. Insert `(consumer_name, event_id)` into `inbox_events` under a unique
   constraint.
4. If it already exists, commit/no-op and acknowledge the message.
5. Otherwise apply local state changes and write any resulting outbox events in
   the same transaction.
6. Commit.
7. Acknowledge JetStream only after commit.

Failures before commit are retried. Poison messages eventually require an
operator-visible dead-letter/advisory path; exact retry counts and backoff are
TBD.

## Identity v1 publisher profile

The first implemented publisher claims at most 100 rows across replicas with
`FOR UPDATE SKIP LOCKED`, a 30-second lease and a per-claim UUID fencing token.
Acknowledgements wait at most 10 seconds; stale workers cannot mark or release
a row claimed by a newer worker. Subjects are
`cookie.events.<event_type>.v<event_version>` in stream `COOKIE_EVENTS`.
`event_id` is sent as `Nats-Msg-Id`; the row is marked published only after the
JetStream publish acknowledgement. Failed attempts are retried indefinitely
with exponential delay from one second to five minutes plus jitter.

NATS outage не делает Identity HTTP API unready. Команда продолжает атомарно
фиксировать business state и outbox; publisher повторяет доставку после
восстановления брокера. Outbox age, attempts/failures и NATS availability должны
быть видимы в telemetry и alerts. Production stream topology provisioned by the
deployment layer has explicit retention/replication quotas and a dedicated
management credential; the Identity credential is publish-only. Identity may
create a bounded single-replica stream only in explicit `dev`/`test` profiles.
В терминах NATS ACL publisher также получает узкий subscribe grant на
`_INBOX.cookie.identity.>`: это reply subject для JetStream acknowledgement, а
не право потреблять domain events.

Verification delivery is a special security boundary: outbox/JetStream contain
only template, expiry and compact JWE (`RSA-OAEP-256` + `A256GCM`). Recipient,
locale and raw one-time token exist only inside the encrypted payload.

## Required operational fields

Outbox records у publishers expose status, attempt count, next-attempt time,
creation time, published time and last error. Inbox records у consumers expose
consumer name, event id, event type and processed time. Retention and cleanup
must preserve enough data to cover the maximum broker redelivery window.

Identity v1 keeps published outbox rows for 7 days, unusable verification
challenges for 30 days, expired refresh families for a further 90 days and
expired rate-limit buckets for a 1-day grace period. Cleanup is bounded and
batched. Consumer inbox retention is selected when a real consumer is built and
must be no shorter than the matching stream/redelivery window.

## Forbidden patterns

- Update PostgreSQL and publish directly to NATS in one request handler.
- Acknowledge a message before the DB transaction commits.
- Use Redis locks as a replacement for inbox uniqueness.
- Read another service's database to repair an event flow.
- Put secrets, tokens, email contents or health/meal detail into logs or broker
  headers.
