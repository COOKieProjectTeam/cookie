# Production on one VM

The production bundle runs Identity, Notification, PostgreSQL, NATS JetStream
and ephemeral Redis on one non-preemptible Yandex VM. The configured floor is
4 vCPU / 8 GiB RAM / 50% core fraction. This remains **non-HA**; external
monitoring, backups, disk snapshots and a tested restore procedure are required
before storing valuable data. Mailpit, the development notification sink and the
legacy Go API are not production services.

Changes in this repository prepare deployable configuration. They do not apply
Terraform, deploy a VM, install real secrets or send notifications. Review the
Terraform plan before expanding an existing VM; resizing can interrupt service.

## Artifact and network boundaries

Each service has its own Container Registry repository, scoped IAM grants,
Dockerfile, CI checks and immutable digest. CI publishes on a successful merge
to `main`; an operator deploys separately. The VM never builds source or stores
static Yandex service-account keys. Its short-lived registry token comes from
metadata using its pull-only service account.

Identity publishes host TCP/8080 and Notification TCP/8082. Terraform permits
these ports only from API Gateway's `198.19.0.0/16` integration range. The existing
gateway routes `/v1/auth/*` and `/v1/notifications/*` and preserves authorization,
idempotency, request IDs and query parameters. Client-supplied `X-Forwarded-For`
is removed. `/healthz` and `/readyz` remain private. Keep the existing gateway
resource/issuer when adding Notification; a new public origin is not required.

Both services join the ordinary `edge` bridge. Notification uses it for outbound
HTTPS (JWKS and FCM) and SMTP with STARTTLS. PostgreSQL, NATS and Redis join only
the internal `backend` bridge, with no host ports. Redis is authenticated and has
no volume, snapshots or AOF: it can be rebuilt from Notification's PostgreSQL
state. The application falls back to its PostgreSQL deadline query during Redis
loss, eviction pressure or restart.

Container memory limits are Identity 1536 MiB, Notification 1536 MiB, PostgreSQL
1536 MiB, NATS 512 MiB and Redis 256 MiB (128 MiB data limit, `noeviction`). JVM
heap is bounded to 70% of its container. These are initial limits; observe actual
usage before increasing concurrency or adding services.

Notification reserves twelve database connections for up to eight push workers,
leaving capacity for HTTP readiness, inbox ingestion and lease heartbeats while
bounded provider calls hold their submission locks.

The production JVM options select Bouncy Castle's pure Java implementation with
`-Dorg.bouncycastle.native.cpu_variant=java`. The pinned LTS provider otherwise
extracts a native CPU probe into `/tmp`, which cannot load on the intentionally
`noexec` tmpfs. This preserves the container restriction and the same Argon2/JWE
algorithms; benchmark password hashing with these production JVM options.

## Installation and secrets

Install this directory as an immutable root-owned bundle, for example
`/opt/cookie/production`. Docker Engine/Compose, curl, jq and flock are required.
Create directories before installing secrets:

```bash
sudo install -d -m 0750 -o 70 -g 70 /srv/cookie/postgres
sudo install -d -m 0750 -o 1000 -g 1000 /srv/cookie/nats
sudo install -d -m 0700 -o root -g root /srv/cookie/releases
sudo install -d -m 0711 -o root -g root /srv/cookie/secrets
sudo install -d -m 0700 -o 70 -g 70 /srv/cookie/secrets/postgres
sudo install -d -m 0700 -o 10001 -g 10001 /srv/cookie/secrets/identity /srv/cookie/secrets/identity/retiring
sudo install -d -m 0700 -o 10001 -g 10001 /srv/cookie/secrets/notification /srv/cookie/secrets/notification/retiring
sudo install -d -m 0700 -o 1000 -g 1000 /srv/cookie/secrets/nats
sudo install -d -m 0700 -o 999 -g 999 /srv/cookie/secrets/redis
sudo install -d -m 0755 -o root -g root /etc/cookie
sudo install -m 0600 -o root -g root .env.example /etc/cookie/production.env
```

Replace every placeholder in the root-owned static environment file. Set both
bind IPs to `0.0.0.0` behind the Terraform gateway, set the exact HTTPS Identity
issuer, and use `<issuer>/v1/auth/jwks` for `COOKIE_IDENTITY_JWKS_URL`. Configure a
real application verification-page URL; the email link must not target Identity's
JSON POST confirmation operation. Configure the SMTP sender and Message-ID
domain. Configure the Firebase project ID when enabling push for Android and
iOS. Never put raw passwords, keys or credential JSON in this file, Git or
Terraform state.

Provision the following files out of band. Private files use mode `0400`; every
path in the table is relative to `/srv/cookie/secrets`.

| Path | Owner | Purpose |
| --- | --- | --- |
| `postgres/admin-password` | `70:70` | Bootstrap/emergency PostgreSQL administrator |
| `postgres/identity-password`, `postgres/identity-migration-password` | `70:70` | Identity runtime/migrator passwords |
| `postgres/notification-password`, `postgres/notification-migration-password` | `70:70` | Notification runtime/migrator passwords |
| `identity/spring.datasource.password`, `identity/spring.flyway.password` | `10001:10001` | Exact copies of corresponding PostgreSQL credentials |
| `identity/cookie.identity.rate-limit-hmac-key` | `10001:10001` | Canonical base64url 32-byte HMAC key |
| `identity/identity-jwt-private.jwk` | `10001:10001` | Active private ES256 P-256 signing JWK |
| `identity/notification-public.jwk` | `10001:10001` | Public half of Notification's active RSA-OAEP-256 key |
| `identity/retiring/*.jwk` | `10001:10001` | Retiring public signing JWKs |
| `identity/nats-identity.creds`, `identity/nats-truststore.jks`, `identity/cookie.identity.nats-truststore-password` | `10001:10001` | Identity NATS identity and trusted CA |
| `notification/spring.datasource.password`, `notification/spring.flyway.password` | `10001:10001` | Exact copies of Notification PostgreSQL credentials |
| `notification/notification-private.jwk`, `notification/retiring/*.jwk` | `10001:10001` | Active/retiring private JWE decryption JWKs |
| `notification/nats-notification.creds`, `notification/nats-truststore.jks`, `notification/cookie.notification.nats-truststore-password` | `10001:10001` | Restricted consumer credential and trusted CA |
| `redis/password` | `999:999` | Random Redis ACL password |
| `notification/spring.data.redis.password` | `10001:10001` | Exact copy of Redis password |
| `notification/cookie.notification.delivery.smtp.password` | `10001:10001` | SMTP password |
| `notification/fcm-credentials.json` | `10001:10001` | Firebase service-account credentials for Android and iOS push |
| `nats/auth.conf`, `nats/server.key`, `nats/stream-admin.creds` | `1000:1000` | Operator/account config, server TLS key and deployment credential |
| `nats/ca.crt`, `nats/server.crt` | `root:root`, `0444` | CA and server certificate with DNS SAN `nats` |

Use different database passwords for every role. The deploy script checks that
owner-specific copies agree, files are regular/non-symlink, permissions are
private and owner UIDs match. Spring reads scalar secrets through
`configtree:/run/secrets/`; no raw production secret is in a Compose environment
variable. Each JKS contains the NATS CA, and hostname verification remains on.
Recheck image UID assumptions when changing pinned images.

FCM is the sole push provider called by Notification for both Android and iOS.
Upload the Apple APNs authentication key to the Firebase project's Apple app
configuration; Firebase delivers iOS messages through APNs. Notification does
not mount an Apple key or connect directly to APNs.

Push is disabled by default and needs no Firebase credential file while
disabled. To enable both platforms, install `notification/fcm-credentials.json`,
set `COOKIE_FCM_PROJECT_ID` and `COOKIE_FCM_ENABLED=true`. The deploy script
automatically adds `compose.fcm.yaml` to mount the credential file. Manual
Compose commands must pass the same overlay when enabling push. Disabled push
does not report a fake successful delivery. Production startup validates the
enabled FCM settings; local development uses Mailpit and disabled push.

The mobile apps must register their Firebase Messaging registration token,
including on iOS. Tokens are opaque and case-sensitive; a native APNs device
token cannot be converted into an FCM token. Migration V006 disables all existing
iOS device registrations and cancels their pending or claimed push jobs. Users
must open the updated iOS app and re-register its FCM token before iOS push can
resume. The migration preserves terminal delivery history and email jobs.

### Key rotation

Notification obtains only public JWT verification keys from the fixed HTTPS
JWKS URL. It does not receive Identity's private signing key. Prepublish a new
verification key before switching signing keys; keep old public keys for the
configured maximum access-token lifetime plus the five-minute JWKS cache window.

JWE decryption keys are independent. Install the new private key in Notification,
retain old private keys in its `retiring` mount, then switch Identity's public
encryption key. Set `COOKIE_NOTIFICATION_RETIRING_PRIVATE_KEY_PATHS` to the
comma-separated container paths under `/run/notification-retiring`. Retain old
keys at least the seven-day broker window and until no unexpired delivery refers
to them. Expired verification deliveries are discarded, not replayed as email.

After replacing mounted key/credential files, explicitly redeploy the affected
service even when its image digest is unchanged (`--notification <current-digest>`
or `--identity <current-digest>`). Compose file-backed mounts can keep the old
inode after atomic host replacement; provider credentials are startup-loaded.
Wait for readiness after installing Notification's new private key before
switching/restarting Identity's public encryption key. A file-reload loop alone
does not replace a mounted inode or update a configured retiring-key path list.

## PostgreSQL provisioning on existing volumes

Identity's historical initialization script applies only to an empty cluster.
Adding Notification never depends on Docker re-running that script. The
`notification-db-provision` one-shot job authenticates with the administrator
secret, creates the missing database and roles and converges grants on each run.
It preserves existing Identity/Notification rows and fails if an existing
Notification database has an unexpected owner.

`notification_migrator` owns Notification's database/schema and runs Flyway;
`notification_app` has CONNECT/USAGE/DML and sequence grants only. Neither role
is superuser, can create databases/roles, bypass RLS, or read Identity tables.
The administrative credential is mounted only into PostgreSQL and its provisioning
job. This job runs before Notification starts, including upgrades over a
previous Identity-only data volume. Provisioning does not imply a schema rollback.

## NATS provisioning and least privilege

Generate operator/account/user material offline with `nsc`, not on the VM. Install
one operator, system account, an application account with JetStream enabled and
a memory resolver with their account JWTs. No anonymous/default user is allowed.
NATS client traffic is TLS-only. Identity retains its three publish subjects
and reply subscription `_INBOX.cookie.identity.>`; it cannot consume events.

`config/notification-nats-permissions.json` is the exact permission fragment for
Notification's application-account user. It grants consumer INFO/NEXT for
`COOKIE_EVENTS.notification-v1`, ACK subjects scoped to that stream/consumer,
and only `_INBOX.cookie.notification.>` subscriptions. Both supported ACK layouts
are listed. It does not grant domain publishing, stream reads, arbitrary reply
subscriptions, or creation/deletion of consumers. Broker subject filters come
from `config/notification-consumer.json`, validated against `service.yaml` by CI.

The deploy-only credential may read/create/edit `COOKIE_EVENTS` and
`notification-v1`, plus its own reply inbox. It stays outside application
containers. Extend the original stream-admin permissions with only:

```text
$JS.API.CONSUMER.INFO.COOKIE_EVENTS.notification-v1
$JS.API.CONSUMER.CREATE.COOKIE_EVENTS.notification-v1
$JS.API.CONSUMER.DURABLE.CREATE.COOKIE_EVENTS.notification-v1
```

Use the subject forms emitted by the pinned nats CLI and validate credentials
against the isolated broker before installation. The init job converges the
stream and consumer without delete/recreate or cursor reset. Existing incompatible
consumer settings require an operator-reviewed migration; never hide that failure
by discarding a durable consumer.

The consumer uses DeliverAll, explicit ACK, 30-second ACK wait, at most 128
outstanding messages, and unlimited transient retries. PostgreSQL inbox commit
precedes ACK. Poison records are durably quarantined by the application. The
stream retains seven days / 1 GiB / one million messages; inbox deduplication
retains 30 days. Alert on consumer lag, oldest quarantine, failed delivery age,
Redis/NATS errors and DB pool saturation.

Quarantine replay is an operator action with a **separate** credential. Do not
add `$JS.API.STREAM.MSG.GET.COOKIE_EVENTS` to Notification's runtime user: it
would bypass its subject filters. The replay procedure must use broker retention
and preserve the original event identity. Telemetry storage/export backends
remain a separate architecture decision; counters alone are not installed alerts.

Delivery gauges are `cookie.notification.delivery.pending`,
`cookie.notification.delivery.oldest.age.seconds`,
`cookie.notification.delivery.unknown` and `cookie.notification.delivery.failed`.
Treat `cookie.notification.delivery.metrics.healthy=0` as a failed metrics snapshot,
not an empty queue. Inspect `cookie.notification.delivery.attempt` by channel and
outcome when investigating growing age or failure counts. Provider responses with
an unknown outcome require operator review and are not automatically resent.

### Quarantine replay command

Install an operator credential as a separate `10001:10001`, `0400` file outside
the normal Notification secret mounts. Its only broker grants are publish
`$JS.API.STREAM.MSG.GET.COOKIE_EVENTS` and subscribe
`_INBOX.cookie.notification_operator.>`. The operator can thereby retrieve a
stored quarantined stream sequence; it cannot republish an event.

```bash
sudo docker compose \
  --project-directory /opt/cookie/production \
  --env-file /etc/cookie/production.env \
  --env-file /srv/cookie/releases/current.env \
  --file /opt/cookie/production/compose.yaml \
  run --rm --no-deps \
  --volume /srv/cookie/secrets/operators/notification-replay.creds:/run/secrets/operator.creds:ro \
  notification \
  --spring.profiles.active=notification-replay \
  --spring.main.web-application-type=none \
  --cookie.notification.consumer-enabled=false \
  --cookie.notification.scheduler-enabled=false \
  --cookie.notification.delivery.enabled=false \
  --cookie.notification.delivery.fcm.enabled=false \
  --cookie.notification.operator-nats-credentials-path=/run/secrets/operator.creds \
  --notification-replay-id='<quarantine-UUID>'
```

The short-lived process inherits Notification database/truststore/key mounts,
uses a separate broker connection for the privileged lookup, verifies the stored
message hash/subject, preserves the original event ID and closes after replay.
It does not run background consumption, scheduling or provider delivery. A
message removed by broker retention causes an explicit failure. Do not bypass
UNKNOWN delivery outcomes or synthesize a replacement event to force sending.

## Deploy, recovery and rollback

Initial deployment on an empty host requires both image digests:

```bash
sudo /opt/cookie/production/deploy.sh \
  --identity 'cr.yandex/<registry-id>/identity@sha256:<64-hex-digest>' \
  --notification 'cr.yandex/<registry-id>/notification@sha256:<64-hex-digest>'
```

For an existing Identity release, install the new bundle/config/secrets and run
only `--notification <digest>`. The old Identity-only manifest is read safely,
its digest is preserved and Identity is not recreated. Subsequent deployments
can specify either service independently. Supplying a service explicitly also
allows reapplying configuration at the same digest. The original single
positional Identity-digest invocation remains supported after both images are
recorded.

Deployment serializes with `/run/lock/cookie-production-deploy.lock`, gets a
short-lived registry token, validates/pulls immutable artifacts, starts shared
infrastructure, provisions Notification's DB and durable consumer, then starts
only requested services. Notification is explicitly stopped before its replacement
starts, including recovery and rollback; Flyway then runs inside the new process.
This coordinated restart prevents old APNs workers from overlapping migration
V006 or new FCM token registration. Do not run extra old Notification replicas
or a separate migrator alongside this rollout. Container and published-port
readiness must pass.
Redis failure permits PostgreSQL scheduling fallback. No deploy step sends a
real email or push as a health check.

The root-owned `releases/current.env` atomically records both digests only after
success. `releases/pending.env` records the old/new pair and services attempted
before each restart. Failures and TERM/INT roll back attempted services in reverse
order; first-install failures stop the new service. A KILL/power interruption
leaves the journal for recovery at the next invocation. A journal left after a
successful manifest commit is cleared without rolling back that commit. If
recovery fails, the script retains the journal and stops for operator recovery.
It never sources release/journal text as shell code.

An image rollback does **not** roll back Flyway, provider requests, Redis state,
NATS stream configuration, secrets, or deployment-bundle changes. Keep migrations
forward-compatible with the previous image and review bundle/secret compatibility
before rollout. Rolling back the image after V006 does not reactivate old iOS
tokens or restore the old token format. Recover with an FCM-capable image; if an
older image is temporarily required for incident recovery, keep push disabled
until the FCM-capable service is restored. Never send newly registered FCM tokens
through the old direct APNs adapter. An incompatible migration is a
restore/incident operation.

Before real traffic, verify gateway header/client-IP behavior, actual SMTP and
push credentials in a controlled test, external HTTPS monitoring, snapshot/restore,
and disk/VM alerts. Configuration tests do not establish production readiness by
themselves.

## Disposable production rehearsal in CI

The `Production deployment rehearsal` job builds both real service Dockerfiles
and runs `.github/scripts/test-production-containers.py` as root on a fresh
Linux GitHub runner. It refuses an existing `/srv/cookie` or deployment lock.
The test generates short-lived JWKs, a CA and certificates, JKS truststores,
NATS operator/account/user JWTs and independent role passwords. No fixture
private key, credential, email or token is committed or uploaded as an artifact.

The original production `compose.yaml` and `deploy.sh` execute without source
rewriting. A test overlay resolves the disposable HTTPS/SMTP host and installs
its CA in Notification's JVM truststore; production profiles, hostname
verification, SMTP authentication and required STARTTLS remain enabled. Real
PostgreSQL, Redis and TLS/JWT NATS run with the production secret mounts, UIDs,
permissions, consumer filters and resource limits. A strict local SMTP fixture
accepts email only after STARTTLS and authentication.

The rehearsal covers a fresh two-service installation, invalid secret-mode
rejection, and registration → encrypted event → SMTP → confirmation → JWT
authenticated Notification API, including opaque 4096-character Android and
mixed-case iOS FCM token registration through the runtime database role. A second
run first starts only the actual Identity image and populates its real schema
through registration, then adds
Notification to that same PostgreSQL directory. It verifies data preservation
and that Identity's container ID does not change during Notification installation,
independent rollout, failed-image rollback and recovery after a SIGKILL leaves
the deployment journal. Migrations remain applied during image rollback.

Only cloud-specific transport is substituted: VM metadata returns a synthetic
token, registry login is local, and synthetic `cr.yandex/...@sha256:` references
map to already-built immutable Docker image IDs. Docker/Compose execution,
container states, readiness requests, file ownership, locks and process signals
are real. This test does not validate Yandex IAM, registry availability, gateway
routing, public DNS/certificates, real provider credentials or VM capacity under
load. Those remain deployment acceptance checks; CI never deploys production.

The job installs `.github/scripts/production-test-requirements.txt` in a private
virtual environment and invokes:

```bash
sudo --preserve-env=GITHUB_ACTIONS,PATH \
  .cache/production-rehearsal-venv/bin/python \
  .github/scripts/test-production-containers.py \
  --identity-image cookie/identity-ci:<commit> \
  --notification-image cookie/notification-ci:<commit>
```
