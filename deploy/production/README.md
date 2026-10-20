# Production on one VM

This directory is the deliberately small first production topology. It runs
only the implemented Identity Service, PostgreSQL and NATS JetStream on one
non-preemptible VM. Mailpit, `notification-sink` and the legacy Go API are
development tools and are intentionally absent.

The topology is **not highly available**: the VM and its attached data disk are
single points of failure. Before accepting real users, configure disk/database
backups, external monitoring and restore tests. The production Notification
Service is not implemented either, so registration emails will remain queued in
`COOKIE_EVENTS`; do not open registration to users until that consumer and its
key-management procedure exist.

## What the bundle guarantees

- Identity is accepted only as an immutable
  `cr.yandex/<registry-id>/identity@sha256:<digest>` reference. The VM never
  builds source code.
- PostgreSQL 18.6 and NATS 2.14.6 use versioned tags plus OCI index digests.
- PostgreSQL and JetStream data live on host paths, not the VM boot filesystem's
  container layer.
- NATS is TLS-only for clients and uses operator/account/user credentials.
  PostgreSQL and NATS have no published host ports.
- Identity connects as restricted `identity_app`; Flyway uses the separate
  non-superuser owner `identity_migrator`. The `postgres` superuser credential is
  mounted only into PostgreSQL.
- Raw values are mounted as files. Spring reads the four scalar Identity
  secrets through `configtree:/run/secrets/`; no raw secret is placed in a
  Compose environment variable.
- Compose falls back to a loopback bind, while the Terraform-managed deployment
  explicitly uses `0.0.0.0` behind a security group that admits port 8080 only
  from API Gateway's `198.19.0.0/16` source range. `/healthz` and `/readyz` are
  not public gateway routes. Identity alone joins a normal `edge` bridge for
  that published port; PostgreSQL and NATS remain only on the isolated
  `backend` bridge.
- The gateway may expose only Identity's generated `/v1/auth/*` routes; it must
  preserve `Authorization`, `Idempotency-Key`, request IDs and query parameters,
  while removing a caller-supplied `X-Forwarded-For` header.
- `deploy.sh` serializes deploys, obtains a short-lived IAM token from VM
  metadata, pulls the selected digest, converges `COOKIE_EVENTS`, waits for
  readiness and attempts an image rollback on failure.

An image rollback does not roll back Flyway migrations. A previous image must
remain forward-compatible with all migrations already applied by the candidate.
Treat an incompatible migration as a restore/incident procedure, not as a
normal rollback.

## Host layout and ownership

Install this directory as an immutable, root-owned bundle such as
`/opt/cookie/production`. The deploy command assumes Docker Engine with the
Compose plugin, `curl`, `jq` and `flock` are installed by VM bootstrap.

Create persistent paths before the first deploy:

```bash
sudo install -d -m 0750 -o 70 -g 70 /srv/cookie/postgres
sudo install -d -m 0750 -o 1000 -g 1000 /srv/cookie/nats
sudo install -d -m 0700 -o root -g root /srv/cookie/releases
sudo install -d -m 0711 -o root -g root /srv/cookie/secrets
sudo install -d -m 0700 -o 70 -g 70 /srv/cookie/secrets/postgres
sudo install -d -m 0700 -o 10001 -g 10001 /srv/cookie/secrets/identity
sudo install -d -m 0700 -o 10001 -g 10001 /srv/cookie/secrets/identity/retiring
sudo install -d -m 0700 -o 1000 -g 1000 /srv/cookie/secrets/nats
```

UID/GID `70` is the user in the pinned PostgreSQL Alpine image. Compose runs
NATS and the non-root NATS toolbox explicitly as `1000:1000`; the Identity image
runs as `10001:10001`. Re-check these ownership assumptions whenever a base
image changes.

Copy `.env.example` to `/etc/cookie/production.env`, replace every placeholder,
then make it root-only:

```bash
sudo install -d -m 0755 -o root -g root /etc/cookie
sudo install -m 0600 -o root -g root .env.example /etc/cookie/production.env
```

Terraform provisions API Gateway, so install `COOKIE_IDENTITY_BIND_IP=0.0.0.0`
and `COOKIE_IDENTITY_TRUSTED_PROXY_CIDRS=198.19.0.0/16` together. The security
group admits that same source CIDR and blocks direct public TCP/8080. The issuer
must be the exact externally visible HTTPS origin from Terraform output,
including the temporary Yandex service domain while there is no custom domain.
The Compose fallback `127.0.0.1` is only for a deliberately gateway-less SSH
tunnel configuration.

Before public traffic, send requests from one client with different forged
`X-Forwarded-For` values and verify they still consume one application IP scope;
then repeat from two real source addresses and verify two scopes. Yandex documents
how HTTP integration removes overridden original headers, but not whether this
integration injects a normalized client address. If the second check fails, leave
`COOKIE_IDENTITY_TRUSTED_PROXY_CIDRS` empty and enforce IP limits at the gateway
until the platform behavior is confirmed.

## Required files

Provision these files through a secure out-of-band channel. None belongs in Git:

| Host path below `/srv/cookie/secrets` | Owner/mode | Purpose |
| --- | --- | --- |
| `postgres/admin-password` | `70:70`, `0400` | PostgreSQL bootstrap/emergency superuser password |
| `postgres/identity-password` | `70:70`, `0400` | Password used while creating `identity_app` |
| `postgres/identity-migration-password` | `70:70`, `0400` | Password used while creating `identity_migrator` |
| `identity/spring.datasource.password` | `10001:10001`, `0400` | Exact copy of the `identity_app` password |
| `identity/spring.flyway.password` | `10001:10001`, `0400` | Exact copy of the `identity_migrator` password |
| `identity/cookie.identity.rate-limit-hmac-key` | `10001:10001`, `0400` | Canonical base64url 32-byte HMAC key |
| `identity/cookie.identity.nats-truststore-password` | `10001:10001`, `0400` | JKS truststore password |
| `identity/identity-jwt-private.jwk` | `10001:10001`, `0400` | Active private P-256 ES256 signing JWK |
| `identity/notification-public.jwk` | `10001:10001`, `0400` | RSA-OAEP-256 public encryption JWK |
| `identity/nats-identity.creds` | `10001:10001`, `0400` | Restricted Identity NATS user credential |
| `identity/nats-truststore.jks` | `10001:10001`, `0400` | JKS containing the NATS CA certificate |
| `identity/retiring/*.jwk` | `10001:10001`, `0400` | Optional retiring ES256 public JWKs during rotation |
| `nats/auth.conf` | `1000:1000`, `0400` | Rendered operator and memory-resolver config |
| `nats/server.key` | `1000:1000`, `0400` | NATS TLS private key |
| `nats/server.crt` | `root:root`, `0444` | NATS TLS server certificate |
| `nats/ca.crt` | `root:root`, `0444` | CA used by NATS clients |
| `nats/stream-admin.creds` | `1000:1000`, `0400` | Deploy-only JetStream administrator credential |

Enter secret values through a protected file or password manager, not as shell
arguments. Runtime and migration passwords have one PostgreSQL-owned copy and
one Identity-owned copy so each container can read only its material;
`deploy.sh` refuses to proceed if either pair differs. Use three independent,
random database passwords.

`postgres-init-identity.sh` creates the restricted roles only when PostgreSQL
initializes an empty data directory. If this bundle is ever applied to a volume
created by an older Compose definition, create/migrate those roles explicitly
under a reviewed database change before starting Identity; Docker entrypoint
init scripts do not rerun against existing clusters.

The NATS server certificate must contain the DNS SAN `nats`, because that is the
Compose service name verified by the Identity client. Build the Identity JKS
from the same CA certificate. The JKS must contain at least one certificate.

### NATS authorization prerequisites

Generate operator/account/user material offline with `nsc`; do not generate it
on the production VM. Render `nats/auth.conf` with:

- one operator and a system account;
- one application account with JetStream enabled;
- a memory resolver preloaded with the system and application account JWTs;
- no anonymous/default user.

The Identity user in the application account may publish only:

```text
cookie.events.account.activated.v1
cookie.events.account.deletion.requested.v1
cookie.events.notification.email.requested.v1
```

It may subscribe only to `_INBOX.cookie.identity.>` for publish
acknowledgements. It must not receive `$JS.API.>` stream-management permission
or any event subscription.

Create a separate `stream-admin.creds` in that same application account. It
needs only the JetStream API subjects required to read, create and update
`COOKIE_EVENTS`, plus its own reply inbox. Keep this credential out of the
Identity container. Validate the rendered server config and both credentials in
an isolated container/network before installing them; the repository does not
pretend to automate operator seed handling.

## First and subsequent deploys

The VM service account needs only `container-registry.images.puller` for the
Identity repository. The metadata endpoint then supplies the short-lived token used
by `deploy.sh`; no static Yandex key is stored on disk.

Run a deployment with the digest printed by the successful image-publish job:

```bash
sudo /opt/cookie/production/deploy.sh \
  'cr.yandex/<registry-id>/identity@sha256:<64-hex-digest>'
```

The script performs these operations in order:

1. checks paths, static configuration and required material;
2. locks `/run/lock/cookie-production-deploy.lock`;
3. logs into Yandex Container Registry using the VM metadata token and a
   temporary Docker configuration directory;
4. pulls the requested Identity digest and the pinned infrastructure images;
5. starts PostgreSQL and NATS and waits for their container health checks;
6. runs the repeatable NATS init job (`add` when absent, `edit --force` when
   present) from `config/cookie-events.json`;
7. starts Identity with the requested digest and verifies `/readyz` both inside
   the container and through the host-published port;
8. atomically records the verified digest in `/srv/cookie/releases/current.env`
   only after both readiness checks pass, or restarts the previous verified
   image if startup or readiness fails.

The release file keeps the last verified digest if deployment is interrupted.
An interrupted candidate may still be running; rerun `deploy.sh` with the desired
digest to check it or restore the digest saved in `current.env`. After an
interrupted first deployment there is no verified release yet and no automatic
rollback target.

`COOKIE_EVENTS` uses file storage, `cookie.events.>`, a seven-day/1 GiB/one
million-message limit, a 1 MiB message limit, ten-minute deduplication and one
replica. The init job preserves the stream and its consumers, but converging
retention limits can expire stored messages under the resulting stream policy.

## Operations

Always pass the static environment and current release explicitly for manual
read-only inspection:

```bash
set -a
source /etc/cookie/production.env
source /srv/cookie/releases/current.env
set +a
docker compose \
  --project-directory /opt/cookie/production \
  --env-file /etc/cookie/production.env \
  --file /opt/cookie/production/compose.yaml \
  ps
```

Inspect recent logs with the same prefix plus `logs --tail 100 identity`. Docker
rotates local JSON logs at five 10 MiB files per container. Do not use
`docker compose down --volumes`; data is bind-mounted, but destructive lifecycle
commands still create avoidable outage risk.

Before real traffic, prove a restore on a disposable VM: restore PostgreSQL and
JetStream data from the selected backup point, install the matching secret/key
set, deploy a schema-compatible Identity digest, and verify `/readyz`, token
verification and outbox publishing. A one-VM topology is an explicit cost tradeoff,
not a substitute for that recovery procedure.
