# Mobile auth coordinator

Generated Kotlin Multiplatform code is an HTTP transport client. It does not
own authentication state, secure persistence, request serialization or deep
link lifecycle. Mobile UI calls a stateful auth coordinator; it must not call
the generated auth endpoints directly.

## Security boundary

Common code defines coordinator and storage interfaces. Platform adapters use:

- an Android Keystore key to encrypt one atomic session record stored outside
  the Keystore;
- an iOS Keychain item configured as device-only and non-synchronizing.

Registration proofs and refresh tokens must not appear in ordinary preferences,
cloud/device backup, clipboard, analytics, crash reports, URLs or logs. Access
tokens may remain in memory and be recreated through refresh after process
restart. A diagnostic representation always redacts every bearer value.

The secure registration store is a map keyed by `registrationAttemptId`, not a
single global slot. More than one attempt can legitimately be pending while
messages are delayed or a user restarts registration.

## Registration lifecycle

```text
IDLE
  -> persist(attemptId, proof)
  -> REGISTERING
  -> AWAITING_EMAIL
  -> CONFIRMING
  -> CONFIRMED
  -> LOGIN
```

Before the first register request, the coordinator generates a random UUID and
an independent 32-byte proof and durably stores them as one record. An in-process
network retry repeats the same id, proof, email, password and locale. The proof
is retained after `202`; it is deleted only after a definite, possibly retried,
confirmation `204`.

The coordinator does not persist the plaintext password for crash recovery. If
the process dies while the register result is unknown, it must not reuse the old
attempt id with a newly entered or changed payload. It asks for the password and
starts a new attempt with a new id/proof. A deliberately abandoned local attempt
has its proof deleted, making delayed links for that attempt unusable.

An incoming verification token contains the public attempt id. The coordinator:

1. validates the expected token shape and never logs it;
2. reads the matching proof from the map;
3. calls confirm with the token and proof;
4. retries an uncertain response with the identical pair;
5. removes the record only after `204`.

Missing proof is not silently replaced and does not fall back to a different
attempt. The UI explains that verification must continue in the app installation
that owns the attempt, or offers an explicit restart which sends a new email.

The development Notification sink currently displays the raw token in Mailpit.
That is a development handoff, not a production deep link. After mobile IDs and
a verified HTTPS domain exist, Notification will render a universal/app link
containing only the verification token. The OS association and coordinator will
route that token through the same steps above; the registration proof is never
placed in the link.

## Session and refresh lifecycle

The secure session record contains the current refresh token and, while a
rotation is unresolved, its in-flight operation:

```text
SessionRecord(
  refreshToken,
  inFlightRefresh = null | (predecessorToken, idempotencyKey),
)
```

Only one coroutine may refresh a session at a time. The coordinator enforces
this with a process-wide mutex and performs the following sequence:

1. load the current secure session record;
2. if there is no in-flight operation, generate a cryptographically random
   UUIDv4 `Idempotency-Key` and atomically persist it with the predecessor
   refresh token before HTTP I/O;
3. call refresh with exactly that token and key;
4. for `429` or retryable `503`, keep the in-flight pair, respect `Retry-After`
   and repeat that pair; an intermediary response may arrive after an upstream
   commit, so the status alone never authorizes a new token or key;
5. for an uncertain network result, repeat exactly the same pair after bounded
   backoff, including after process restart;
6. on success, atomically replace the record with the successor refresh token
   and clear the in-flight operation;
7. on an unrecoverable `401`, clear the unusable local session and require login.

A generic Ktor retry plugin must not create a new idempotency key, and UI
callers never supply one. Concurrent requests wait for the same refresh result
instead of starting independent rotations. A repeated response may carry a new
access JWT, but its successor refresh token is identical. Exact recovery has no
wall-clock replay deadline: it remains available while that immediate successor
is current and the family is active and unexpired. It is nevertheless bounded
by state: after the successor rotates, or the family is revoked or expires, the
old pair cannot return bearer material. The coordinator stops eager retries when
its foreground/network budget is exhausted and leaves the in-flight pair
durably stored for a later resume; it must never spin indefinitely.

The in-flight predecessor/key pair is bearer-sensitive recovery material. While
its immediate successor remains current, possession of both values is equivalent
to possession of that successor and can mint fresh access JWTs. The random UUIDv4
contributes 122 bits of recovery-secret entropy, but neither it nor the pair may
enter logs, telemetry, crash reports, clipboard or backup.

Logout uses the current cryptographically valid refresh token. After a definite
or safely repeated logout response, the coordinator deletes the whole local
session record. Clearing local credentials without reaching the server is a
local sign-out and must be described as such because an already issued access
token remains valid until expiry.

## Account deletion lifecycle

Account deletion is an authenticated asynchronous command, not a local logout.
Before `POST /v1/auth/account-deletion-requests`, the coordinator creates a
cryptographically random RFC 4122 UUIDv4 `Idempotency-Key` and keeps it in
protected, non-synchronizing storage while the result is unresolved. Network
retry repeats the same key and the same current password. Plaintext password is
never persisted: after process death the user must enter it again and the
coordinator reuses the stored key only if it can still obtain a valid access
JWT. The key, password and access JWT never enter logs, analytics, crash reports,
clipboard, URLs or backup.

The UI does not supply account id or device id. Identity derives account id only
from the verified access-token `sub`, then applies `30/IP/hour` and
`10/account/hour` limits before password hashing. After authentication,
validation, rate-limit and availability gates, a successful or exact-retry
response is `202` with the original `deletionRequestId`, `DELETION_PENDING` and
`requestedAt`. A retry can still receive `429` or retryable `503`; it keeps the
same key and respects `Retry-After`. A lost response is retried; it must not be
replaced by an optimistic local success screen.

There is an explicit v1 recovery limit: if a successful response is lost, the
process then dies, the in-memory access JWT disappears and the committed command
has already revoked refresh, the client has no authenticated status operation
with which to resolve the result. It retains an unresolved local marker and must
not claim either completion or failure. A later status/recovery contract is part
of the distributed-completion stage.

Only after a definite `202` does the coordinator delete the whole secure session
and any in-memory access token, then present deletion as pending rather than
completed. There is no cancellation or grace period in v1. Server-side refresh
families are revoked, but an access JWT issued before acceptance remains
cryptographically valid until `exp`. This residual window is up to the effective
configured access-token TTL (15 minutes by default; configuration currently permits
up to 24 hours) because no denylist or introspection exists. Clearing local
state reduces accidental reuse but cannot shorten that window for a copied token.

Downstream consumers and acknowledgements are not implemented in v1, so the
client must not display a completed-deletion state. A later product flow may add
status reporting only together with the distributed completion protocol.

## Required tests before UI integration

- process death before register, after `202`, and around confirm `204`;
- multiple pending attempts and an out-of-order verification message;
- malformed link, unknown attempt id and missing proof;
- process death immediately before and after refresh HTTP I/O;
- two API calls observing an expired access token concurrently;
- `429`, retryable `503`, lost success response and stale exact retry;
- secure-store write failure without partial token replacement;
- logout racing with an in-flight refresh;
- account deletion racing with refresh/login and process death around its `202`;
- exact deletion retry, competing keys and secure removal only after definite acceptance;
- assertions that logs and analytics never contain proof, verification token,
  access token, refresh token, current password or `Idempotency-Key`.
