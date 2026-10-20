# ADR 0013: Notification, durable delivery and recoverable Redis scheduling

- Status: accepted
- Date: 2026-09-17
- Updated: 2026-10-20 — FCM is the sole push submission provider for Android and iOS.
- Supersedes: Identity-only deployment scope of ADR 0012; Notification target-only details in the August Miro snapshot.

## Decision

Notification is the second Kotlin/JVM service and the first durable event consumer.
It follows Identity's domain/application/adapter module boundaries, generated
service-owned public OpenAPI and independent release/test conventions. The small
service scaffold under `backend/tools/service-template` preserves this structure.
Notification owns its PostgreSQL database, inbox, projections, schedules, devices
and delivery queue. It publishes no domain events and therefore has no outbox.
The first inbox implementation remains service-local until another real consumer
validates a shared abstraction.

Redis is an expendable sorted-set deadline index. PostgreSQL authorizes every
claim and enforces logical occurrence uniqueness; periodic indexed PostgreSQL
polling continues even when Redis is healthy. Redis loss or a crash between
commit and index update cannot lose work.

Provider calls happen outside ingestion transactions. The consumer acknowledges
only committed local effects. Provider acceptance is recorded as `ACCEPTED`,
not receipt/read confirmation. Retries are bounded by expiry and provider policy. A persistent retryable-provider-
failure counter is separate from claim/audit counts; technical deferrals and
unavailable channels do not consume the ten-failure budget or advance backoff.
For SMTP, the explicit product decision is **no automatic retry after an ambiguous
submission**: a persisted `SMTP_SUBMITTING` marker precedes `sendMessage`; a lost
response, crashed process or expired submitting lease becomes terminal `UNKNOWN`.
This may lose an email even when the process crashed just before submission.
The user can request another verification event. Broker redelivery/operator replay
never sends the same UNKNOWN job again.

Push submission uses a separate bounded transaction, sharing the account and
token locks with device registration and account deletion. It rechecks ownership,
preferences and projections, calls the bounded provider adapter and records the
outcome before releasing those locks. This intentionally serializes a token
transfer or deletion with an in-flight submission; it cannot recall a request
already submitted to a provider. The ingestion transaction never calls providers.
Both IOS and ANDROID register opaque, case-sensitive FCM registration tokens.
Their ownership locks and unique digest index share the same FCM namespace,
independent of the caller-supplied platform. V007 preserves the latest active
registration when consolidating historical cross-platform duplicates.
A trigger-maintained SHA-256 digest provides bounded unique index entries without
reducing the public token length limit.

FCM HTTP v1 handles push submission for both platforms. For iOS, FCM forwards the
message to APNs using the Apple credentials configured in Firebase; Notification
does not connect to APNs or hold its signing key. Platform-specific expiry and
collapse identifiers remain in the FCM payload. This keeps one backend provider
integration while preserving Apple delivery semantics. SMTP remains the email
channel. Mobile clients must integrate Firebase Messaging and send refreshed FCM
tokens through the existing device API. Migration V006 disables legacy iOS APNs
registrations and cancels their active push jobs; those tokens cannot be converted.
Android registrations and terminal delivery evidence remain unchanged.

Inbox, delivery and quarantine metadata are retained for 30 days. Poison-message
quarantine stores identifiers, safe reason codes and content hashes, never payloads.
An operator-only replay process can retrieve the original broker message while it
still exists. Seven-day stream age is a maximum, not a recovery guarantee: byte
and count limits can evict messages earlier. Account tombstones remain until a
separate safe lifecycle protocol exists; Notification does not finalize Identity
deletion or fabricate acknowledgement events.

## Contract changes

Notification receives versioned full snapshots from future Nutrition, Health and
Progress producers. They have no deployed v1 producer yet; these are explicit
target-contract amendments, covered by consumer fixtures. Existing Identity
event shapes and security envelopes remain unchanged. Future producer rollout
must implement these snapshots atomically with its business mutations/outbox.

Progress owns effective goals including overrides and cheat days. Notification
does not subscribe directly to User goal/override changes or read domain data
over HTTP. Day snapshots carry their timezone; a mismatched timezone/date or
unknown goal suppresses goal-dependent notifications rather than assuming zero.
The Notification settings API owns its scheduling timezone.

Notification discovers Identity's public signing keys at a fixed configured JWKS
origin with verified TLS and bounded caching. This public, unauthenticated
key-discovery endpoint is explicitly distinct from protected internal operations;
it does not grant access to Identity data. SMTP submission is also recorded in
the synchronous dependency model.

## Deployment and limits

Local Compose replaces the default sink with Notification and Mailpit. Production
configuration adds Notification and Redis to the existing single non-HA Yandex VM,
isolated database roles, scoped broker/Redis ACLs, independent images and a
two-service release manifest. Initial capacity is configured at 4 vCPU / 8 GiB;
load testing and operational monitoring must validate this budget.
V008 narrows runtime grants to known application tables, makes account tombstones
append-only, protects Flyway history, and separates quarantine replay bookkeeping
from runtime ingestion/retention. Repeat provisioning reapplies that policy;
new tables require an explicit grant migration.

Provider credentials and a trusted verification HTTPS URL are deployment inputs.
The email contains only the verification token in the URL fragment, never the
client-held registration proof. Domain association and the mobile confirmation
coordinator remain prerequisites for opening registration to users. This change
does not apply Terraform or deploy cloud resources.

Detailed reminder rules and contract semantics are in
[`../architecture/notification.md`](../architecture/notification.md).
