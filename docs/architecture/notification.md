# Notification Service

Notification owns email/push delivery, devices, preferences and reminder state.
Its runtime has independent API, consumer, scheduler and delivery roles in one
deployable application. PostgreSQL is authoritative; Redis is recoverable.

## Public API

`contracts/openapi/public/notification.yaml` generates server interfaces/models
and the active mobile client. The four stable operations are `registerDevice`,
`unregisterDevice`, `getNotificationSettings`, `updateNotificationSettings` under
`/v1/notifications`. Only a verified Identity JWT `sub` selects an account.
Probes remain `/healthz` and `/readyz` outside the public API/gateway.

GET lazily creates default settings unless a deletion tombstone exists. PATCH
merges supplied nested fields and rejects unknown fields, scalar type coercion,
empty sections, explicit nulls, invalid
IANA timezones, invalid `HH:mm` times and food intervals outside 1..10080 minutes.
Timezone is absent until explicitly set; all scheduled push remains paused until
then. Locale supports `ru` and `en`, with Russian fallback. Devices are scoped to
their account, and an enabled platform/token is bound to only one account at a
time. Invalid-token provider responses are conditional on the submitted token
revision, so a delayed response cannot disable a newly registered token.

## Reminder rules

Defaults enable each type, with food inactivity 240 minutes, water inactivity
120 minutes, weigh time 08:00, and quiet hours `[23:00,08:00)`. Equal quiet start/end
disables the quiet interval. First timezone configuration records a scheduling
baseline; existing historical records never trigger an immediate catch-up burst.

- Food: one occurrence per latest-meal cycle; changing the same meal time moves
  a pending deadline, and a newer latest meal starts another cycle. With no meals,
  one initial cycle starts from scheduling activation. A fully unsent cancelled
  occurrence can resume at the corrected deadline with the same delivery IDs;
  recorded submission evidence prevents a delivered cycle from being rearmed.
- Water: at least two hours since the latest water activity/reminder. Quiet hours
  and outages coalesce missed reminders into one current occurrence. A matching
  Progress snapshot with consumed water at or above the positive target suppresses
  reminders. An unknown target does not suppress generic water reminders.
  Queue reservation and actual provider submission have separate timestamps:
  delayed delivery moves the next reminder at least two hours forward. Fanout
  to other devices of the same occurrence is allowed.
- Weigh: once per local date at the selected time, unless an extant measurement
  belongs to that date. Previous dates are not caught up.
- Calories exceeded: once per local date when consumed calories exceed the known
  positive effective target and `unlimitedCalories=false`.
- Calories under target: independently once per date from 20:00 when consumed
  calories are below 80% of that target. Missing data never means zero.

Food/water use elapsed UTC durations; daily schedules and quiet hours use the
chosen IANA zone. A DST gap uses the first valid instant after the gap, an overlap
uses its first occurrence. Timezone changes recalculate future local deadlines
without resetting inactivity; daily deduplication does not include timezone.
Delivery rechecks current preferences, quiet hours, token ownership/revision,
deletion and relevant projections. Invalidated diary data cancels pending pushes.
Daily reminders never cross into another local date.

## Event contracts

All subjects use `cookie.events.<event_type>.v1`. Every message validates its
envelope, expected producer and matching subject before use. Unknown additive
payload fields are ignored. Required Identity events retain their existing shape:
activation, deletion request and encrypted email request. Other events require
positive monotonic `aggregate_version` and the following full snapshots:

| Event | Required Notification data |
|---|---|
| `user.profile.updated` | `userId`; locale updates affect defaults, not an explicitly chosen locale; settings timezone stays authoritative |
| `nutrition.meal.created/updated` | `mealId,userId,eatenAt`; version belongs to the meal |
| `nutrition.meal.deleted` | `mealId,userId`; higher-version tombstone |
| `nutrition.water.changed` | `userId,localDate,totalMl,lastConsumedAt`; the timestamp is explicitly null for an empty day |
| `health.weight.changed` | `measurementId,userId,deleted`; live measurements also contain `measuredAt,valueKg` |
| `progress.day.updated` | `userId,localDate,timezone,calories,water`; version belongs to the daily snapshot |

Progress `calories` contains `consumed,target,unlimitedCalories`; `water` contains
`consumedMl,targetMl`. Each field is present, with explicit null for unknown values.
Negative quantities are invalid. Only a snapshot matching Notification's timezone
and current local date can drive goal warnings or water-goal suppression. There is
no arbitrary freshness timeout on an otherwise valid same-day snapshot. Future
Progress integration must provide a matching daily projection; Notification cannot
recalculate another service's timezone-dependent totals itself.

Store only minimal allowlisted projection fields, not dishes, health values or
arbitrary event payloads. Ignore older/equal versions and retain entity tombstones.
Deletion serializes with user updates, removes owned data, cancels pending jobs and
retains a minimal lifecycle tombstone against late replay. Existing Identity JWTs
may remain valid until expiry; the local tombstone closes Notification access once
the deletion event is received. An already submitted provider request cannot be
recalled.

## Reliability and operations

Inbox uniqueness is `(consumer_name,event_id)`. Ingestion atomically updates the
inbox, projections and schedules/jobs, then acknowledges JetStream. Providers and
Redis are never called inside that transaction. Metadata-only quarantine is
operator-visible, with separately authorized original-message replay; runtime
credentials cannot fetch arbitrary messages from the shared stream.

Redis `cookie:notification:reminder_deadlines` stores opaque deadline identifiers
and versions. Startup/reconnect and bounded periodic reconciliation rebuild it.
A PostgreSQL overdue sweep remains active regardless of Redis health. Workers
recheck authoritative deadlines and occurrence uniqueness before issuing work.

SMTP uses mandatory production STARTTLS, certificate/hostname checks, one recipient
and a stable Message-ID. Only proven pre-submission failures/negative transient
replies can retry. Ambiguous submission is terminal `UNKNOWN`; replay never resets
it. APNs and FCM have separate bounded adapters and provider-aware retry policies.
APNs honors `Retry-After` as seconds or an HTTP date, with a one-minute minimum
for throttling, fifteen minutes for server errors and a one-day maximum. FCM
uses the same parsing, with its own one-minute/ten-second minimums.
Provider acceptance does not prove display or reading. Templates never put diary
details into push payloads, logs or broker headers.

JWE decrypts only in memory, requires the Identity RSA-OAEP-256/A256GCM profile,
matching attempt/expiry metadata, and accepts active/retiring keys by `kid`.
Terminal email jobs erase ciphertext. Secret mounts and key rotation procedures
are documented in the production runbook. JWKS retrieval uses a configured origin,
no redirects/token-controlled key URLs, a five-minute cache and bounded refresh.
Missing usable keys gives `503`; invalid credentials give `401`.

Readiness requires PostgreSQL, completed migrations, validated configuration,
usable Identity signing keys and the mounted Notification decryption key ring.
Redis, NATS and provider outages leave readiness available while their durable
backlogs and error metrics expose the degradation.

Metrics distinguish ingest failures/quarantine, scheduler lag, Redis errors,
provider outcomes, UNKNOWN SMTP and delivery backlog. Grafana remains the target
UI; selecting/deploying telemetry backends is a separate architectural decision.
Initial alerts should include `cookie.notification.delivery.unknown > 0`,
`cookie.notification.delivery.oldest.age.seconds > 60`, growing failed/attempt
counts and `cookie.notification.delivery.metrics.healthy = 0`; tune the backlog
threshold against actual load and provider rate limits.
Scheduler backlog/age is exposed as `cookie.notification.scheduler.overdue` and
`cookie.notification.scheduler.oldest.age.seconds`; NATS reports `pending`,
`unacknowledged` and `healthy` under `cookie.notification.nats`. A zero health
gauge makes the associated last known backlog value stale, not an empty queue.
The real Identity flow is integration-tested; future domain producers are covered
by contract fixtures until their implementation exists.
