#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly BUNDLE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly COMPOSE_FILE="${BUNDLE_DIR}/compose.yaml"
readonly CONFIG_ENV="${COOKIE_PRODUCTION_ENV_FILE:-/etc/cookie/production.env}"
readonly RELEASE_DIR="/srv/cookie/releases"
readonly RELEASE_ENV="${RELEASE_DIR}/current.env"
readonly LOCK_FILE="/run/lock/cookie-production-deploy.lock"
readonly HEALTH_TIMEOUT_SECONDS="${COOKIE_DEPLOY_HEALTH_TIMEOUT_SECONDS:-180}"
readonly PUBLISHED_HEALTH_TIMEOUT_SECONDS=30
readonly NOTIFICATION_MIGRATION_CONTAINER=cookie-production-notification-migrate

die() {
  printf 'deploy: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command is missing: $1"
}

require_owner() {
  local path="$1"
  local expected_uid="$2"
  local actual_uid

  actual_uid="$(stat --format '%u' "${path}")"
  [[ "${actual_uid}" == "${expected_uid}" ]] \
    || die "unexpected owner UID for ${path}: wanted ${expected_uid}, got ${actual_uid}"
}

require_private_file() {
  local path="$1"
  local expected_uid="$2"
  local mode

  [[ -f "${path}" && ! -L "${path}" ]] \
    || die "secret path must be a regular file, not a symlink: ${path}"
  require_owner "${path}" "${expected_uid}"
  mode="$(stat --format '%a' "${path}")"
  (( (8#${mode} & 077) == 0 && (8#${mode} & 0400) == 0400 )) \
    || die "secret file must be owner-readable and inaccessible by group/other: ${path}"
}

require_protected_directory() {
  local path="$1"
  local expected_uid="$2"
  local mode

  [[ -d "${path}" && ! -L "${path}" ]] \
    || die "protected path must be a real directory, not a symlink: ${path}"
  require_owner "${path}" "${expected_uid}"
  mode="$(stat --format '%a' "${path}")"
  (( (8#${mode} & 022) == 0 && (8#${mode} & 0500) == 0500 )) \
    || die "protected directory must be owner-readable/searchable and not writable by group/other: ${path}"
}

require_public_certificate() {
  local path="$1" mode
  [[ -f "$path" && ! -L "$path" ]] || die "certificate must be a regular file: $path"
  require_owner "$path" 0
  mode="$(stat --format '%a' "$path")"
  (( (8#${mode} & 0444) == 0444 && (8#${mode} & 0022) == 0 )) \
    || die "certificate must be readable by service users and not writable by group/other: $path"
}

validate_secret_layout() {
  local secret_root="$1"
  local required_files=(
    "${secret_root}/postgres/admin-password"
    "${secret_root}/postgres/identity-password"
    "${secret_root}/postgres/identity-migration-password"
    "${secret_root}/postgres/notification-password"
    "${secret_root}/postgres/notification-migration-password"
    "${secret_root}/identity/spring.datasource.password"
    "${secret_root}/identity/spring.flyway.password"
    "${secret_root}/identity/cookie.identity.rate-limit-hmac-key"
    "${secret_root}/identity/cookie.identity.nats-truststore-password"
    "${secret_root}/identity/identity-jwt-private.jwk"
    "${secret_root}/identity/notification-public.jwk"
    "${secret_root}/identity/nats-identity.creds"
    "${secret_root}/identity/nats-truststore.jks"
    "${secret_root}/notification/spring.datasource.password"
    "${secret_root}/notification/spring.flyway.password"
    "${secret_root}/notification/cookie.notification.nats-truststore-password"
    "${secret_root}/notification/spring.data.redis.password"
    "${secret_root}/notification/cookie.notification.delivery.smtp.password"
    "${secret_root}/notification/notification-private.jwk"
    "${secret_root}/notification/nats-notification.creds"
    "${secret_root}/notification/nats-truststore.jks"
    "${secret_root}/redis/password"
    "${secret_root}/nats/auth.conf"
    "${secret_root}/nats/ca.crt"
    "${secret_root}/nats/server.crt"
    "${secret_root}/nats/server.key"
    "${secret_root}/nats/stream-admin.creds"
  )
  local path

  require_protected_directory "${secret_root}" 0
  require_protected_directory "${secret_root}/postgres" 70
  require_protected_directory "${secret_root}/identity" 10001
  require_protected_directory "${secret_root}/identity/retiring" 10001
  require_protected_directory "${secret_root}/nats" 1000
  require_protected_directory "${secret_root}/notification" 10001
  require_protected_directory "${secret_root}/notification/retiring" 10001
  require_protected_directory "${secret_root}/redis" 999

  for path in "${required_files[@]}"; do
    [[ -s "${path}" ]] || die "required secret/material file is missing or empty: ${path}"
  done

  require_private_file "${secret_root}/postgres/notification-password" 70
  require_private_file "${secret_root}/postgres/notification-migration-password" 70
  require_private_file "${secret_root}/redis/password" 999
  for path in \
    "${secret_root}/notification/spring.datasource.password" \
    "${secret_root}/notification/spring.flyway.password" \
    "${secret_root}/notification/cookie.notification.nats-truststore-password" \
    "${secret_root}/notification/spring.data.redis.password" \
    "${secret_root}/notification/cookie.notification.delivery.smtp.password" \
    "${secret_root}/notification/notification-private.jwk" \
    "${secret_root}/notification/nats-notification.creds" \
    "${secret_root}/notification/nats-truststore.jks"; do
    require_private_file "$path" 10001
  done
  require_private_file "${secret_root}/postgres/admin-password" 70
  require_private_file "${secret_root}/postgres/identity-password" 70
  require_private_file "${secret_root}/postgres/identity-migration-password" 70
  for path in \
    "${secret_root}/identity/spring.datasource.password" \
    "${secret_root}/identity/spring.flyway.password" \
    "${secret_root}/identity/cookie.identity.rate-limit-hmac-key" \
    "${secret_root}/identity/cookie.identity.nats-truststore-password" \
    "${secret_root}/identity/identity-jwt-private.jwk" \
    "${secret_root}/identity/notification-public.jwk" \
    "${secret_root}/identity/nats-identity.creds" \
    "${secret_root}/identity/nats-truststore.jks"; do
    require_private_file "${path}" 10001
  done
  for path in \
    "${secret_root}/nats/auth.conf" \
    "${secret_root}/nats/server.key" \
    "${secret_root}/nats/stream-admin.creds"; do
    require_private_file "${path}" 1000
  done
  require_public_certificate "${secret_root}/nats/ca.crt"
  require_public_certificate "${secret_root}/nats/server.crt"

  cmp -s \
    "${secret_root}/postgres/identity-password" \
    "${secret_root}/identity/spring.datasource.password" \
    || die "PostgreSQL and Identity runtime-password copies do not match"
  cmp -s \
    "${secret_root}/postgres/identity-migration-password" \
    "${secret_root}/identity/spring.flyway.password" \
    || die "PostgreSQL and Identity migration-password copies do not match"

  cmp -s "${secret_root}/postgres/notification-password" "${secret_root}/notification/spring.datasource.password" \
    || die "PostgreSQL and Notification runtime-password copies do not match"
  cmp -s "${secret_root}/postgres/notification-migration-password" "${secret_root}/notification/spring.flyway.password" \
    || die "PostgreSQL and Notification migration-password copies do not match"
  if [[ "${COOKIE_FCM_ENABLED:-false}" == true ]]; then
    [[ -n "${COOKIE_FCM_PROJECT_ID:-}" ]] || die "enabled FCM requires its project ID"
    [[ -s "${secret_root}/notification/fcm-credentials.json" ]] || die "enabled FCM requires its credentials file"
    require_private_file "${secret_root}/notification/fcm-credentials.json" 10001
  fi
  cmp -s "${secret_root}/redis/password" "${secret_root}/notification/spring.data.redis.password" \
    || die "Redis and Notification password copies do not match"
}

CURRENT_IDENTITY='' CURRENT_NOTIFICATION='' DOCKER_CONFIG=''
PENDING_OLD_IDENTITY='' PENDING_OLD_NOTIFICATION='' PENDING_NEW_IDENTITY='' PENDING_NEW_NOTIFICATION='' PENDING_ATTEMPTED=''
readonly PENDING_ENV="${RELEASE_DIR}/pending.env"

validate_image() {
  [[ "$2" =~ ^cr\.yandex/[a-z0-9]{6,64}/${1}@sha256:[a-f0-9]{64}$ && "$2" == "cr.yandex/${YC_REGISTRY_ID}/${1}@sha256:"* ]] \
    || die "$1 image must be an immutable digest from its configured Yandex repository"
}
read_release() {
  local key value seen='|'
  CURRENT_IDENTITY=''; CURRENT_NOTIFICATION=''
  [[ -e "$RELEASE_ENV" ]] || return 0
  require_private_file "$RELEASE_ENV" 0
  while IFS='=' read -r key value || [[ -n "$key" ]]; do
    [[ "$seen" != *"|${key}|"* ]] || die "duplicate release manifest field"
    seen="${seen}${key}|"
    case "$key" in
      IDENTITY_IMAGE) validate_image identity "$value"; CURRENT_IDENTITY="$value" ;;
      NOTIFICATION_IMAGE) validate_image notification "$value"; CURRENT_NOTIFICATION="$value" ;;
      *) die "unexpected release manifest field: $key" ;;
    esac
  done <"$RELEASE_ENV"
  [[ -n "$CURRENT_IDENTITY" ]] || die "release manifest is missing Identity"
}
write_release() {
  local temporary
  temporary="$(mktemp "${RELEASE_DIR}/current.env.tmp.XXXXXX")"
  printf 'IDENTITY_IMAGE=%s\nNOTIFICATION_IMAGE=%s\n' "$IDENTITY_IMAGE" "$NOTIFICATION_IMAGE" >"$temporary"
  chmod 0600 "$temporary"
  mv -f -- "$temporary" "$RELEASE_ENV"
}
write_pending() {
  local temporary
  temporary="$(mktemp "${RELEASE_DIR}/pending.env.tmp.XXXXXX")"
  printf 'OLD_IDENTITY=%s\nOLD_NOTIFICATION=%s\nNEW_IDENTITY=%s\nNEW_NOTIFICATION=%s\nATTEMPTED=%s\n' \
    "$CURRENT_IDENTITY" "$CURRENT_NOTIFICATION" "$IDENTITY_IMAGE" "$NOTIFICATION_IMAGE" "$PENDING_ATTEMPTED" >"$temporary"
  chmod 0600 "$temporary"
  mv -f -- "$temporary" "$PENDING_ENV"
}
read_pending() {
  local key value seen='|'
  require_private_file "$PENDING_ENV" 0
  PENDING_OLD_IDENTITY=''; PENDING_OLD_NOTIFICATION=''; PENDING_NEW_IDENTITY=''; PENDING_NEW_NOTIFICATION=''; PENDING_ATTEMPTED=''
  while IFS='=' read -r key value || [[ -n "$key" ]]; do
    [[ "$seen" != *"|${key}|"* ]] || die "duplicate pending manifest field"
    seen="${seen}${key}|"
    case "$key" in
      OLD_IDENTITY) [[ -z "$value" ]] || validate_image identity "$value"; PENDING_OLD_IDENTITY="$value" ;;
      OLD_NOTIFICATION) [[ -z "$value" ]] || validate_image notification "$value"; PENDING_OLD_NOTIFICATION="$value" ;;
      NEW_IDENTITY) validate_image identity "$value"; PENDING_NEW_IDENTITY="$value" ;;
      NEW_NOTIFICATION) validate_image notification "$value"; PENDING_NEW_NOTIFICATION="$value" ;;
      ATTEMPTED)
        case "$value" in ''|identity|notification|identity,notification) ;; *) die "invalid pending service order" ;; esac
        PENDING_ATTEMPTED="$value" ;;
      *) die "unexpected pending manifest field: $key" ;;
    esac
  done <"$PENDING_ENV"
  [[ "$seen" == '|OLD_IDENTITY|OLD_NOTIFICATION|NEW_IDENTITY|NEW_NOTIFICATION|ATTEMPTED|' ]] || die "incomplete pending release manifest"
}
compose() {
  local files=(--file "$COMPOSE_FILE")
  [[ "${COOKIE_FCM_ENABLED:-false}" != true ]] || files+=(--file "${BUNDLE_DIR}/compose.fcm.yaml")
  docker compose --project-directory "$BUNDLE_DIR" --env-file "$CONFIG_ENV" "${files[@]}" "$@"
}
stop_notification_migration() {
  # A killed deploy process can leave its one-off container alive. Fence it
  # before rollback/recovery starts workers on the previous release image.
  local container_id
  container_id="$(docker ps --all --filter "name=^/${NOTIFICATION_MIGRATION_CONTAINER}$" --format '{{.ID}}')" || return 1
  if [[ -n "$container_id" ]]; then
    if ! docker stop --time 60 "$container_id" || ! docker rm --force "$container_id" 2>/dev/null; then
      # --rm may already have removed the container when stop completed.
      # A Docker error alone is not evidence that the migrator has stopped.
      container_id="$(docker ps --all --filter "name=^/${NOTIFICATION_MIGRATION_CONTAINER}$" --format '{{.ID}}')" || return 1
      [[ -z "$container_id" ]] || return 1
    fi
  fi
}
recreate_service() {
  local service="$1"
  if [[ "$service" == notification ]]; then
    compose stop "$service" || return 1
  fi
  # Runtime always has Flyway disabled, including a rollback to an older image.
  compose up --detach --no-deps --force-recreate --pull never "$service"
}
upgrade_service() {
  local service="$1"
  if [[ "$service" == notification ]]; then
    compose stop "$service" || return 1
    stop_notification_migration || return 1
    compose run --rm --no-deps --name "$NOTIFICATION_MIGRATION_CONTAINER" notification-migrate || return 1
    compose up --detach --no-deps --force-recreate --pull never "$service"
  else
    recreate_service "$service"
  fi
}
wait_for_healthy() {
  local service="$1" deadline=$((SECONDS + $2)) status='' container_id
  while (( SECONDS < deadline )); do
    container_id="$(compose ps --quiet "$service" 2>/dev/null || true)"
    status=''
    if [[ -n "$container_id" ]]; then
      status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id" 2>/dev/null || true)"
    fi
    case "$status" in healthy) return 0 ;; exited|dead) return 1 ;; esac
    sleep 2
  done
  printf 'deploy: timed out waiting for %s (last state %s)\n' "$service" "${status:-missing}" >&2
  return 1
}
wait_for_published() {
  local service="$1" port deadline=$((SECONDS + PUBLISHED_HEALTH_TIMEOUT_SECONDS)) code
  case "$service" in identity) port=8080 ;; notification) port=8082 ;; *) return 1 ;; esac
  while (( SECONDS < deadline )); do
    code="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 3 "http://127.0.0.1:${port}/readyz" 2>/dev/null || true)"
    [[ "$code" == 200 ]] && return 0
    sleep 2
  done
  printf 'deploy: published %s port did not become ready\n' "$service" >&2
  return 1
}
recover_pending() {
  local service previous failed=false
  [[ -e "$PENDING_ENV" ]] || return 0
  read_pending
  stop_notification_migration || return 1
  read_release
  if [[ "$CURRENT_IDENTITY" == "$PENDING_NEW_IDENTITY" && "$CURRENT_NOTIFICATION" == "$PENDING_NEW_NOTIFICATION" ]]; then
    rm -- "$PENDING_ENV" # Commit succeeded before interruption during journal cleanup.
    return 0
  fi
  [[ "$CURRENT_IDENTITY" == "$PENDING_OLD_IDENTITY" && "$CURRENT_NOTIFICATION" == "$PENDING_OLD_NOTIFICATION" ]] \
    || die "pending and committed releases disagree; manual recovery is required"
  export IDENTITY_IMAGE="${PENDING_OLD_IDENTITY:-$PENDING_NEW_IDENTITY}" NOTIFICATION_IMAGE="${PENDING_OLD_NOTIFICATION:-$PENDING_NEW_NOTIFICATION}"
  for service in notification identity; do
    [[ ",${PENDING_ATTEMPTED}," == *",${service},"* ]] || continue
    if [[ "$service" == identity ]]; then previous="$PENDING_OLD_IDENTITY"; else previous="$PENDING_OLD_NOTIFICATION"; fi
    if [[ -z "$previous" ]]; then
      printf 'deploy: stopping failed first installation of %s\n' "$service" >&2
      compose stop "$service" || failed=true
      continue
    fi
    printf 'deploy: restoring previous %s image %s\n' "$service" "$previous" >&2
    if ! docker image inspect "$previous" >/dev/null 2>&1 && ! docker pull "$previous"; then failed=true; continue; fi
    if ! recreate_service "$service" \
      || ! wait_for_healthy "$service" "$HEALTH_TIMEOUT_SECONDS" || ! wait_for_published "$service"; then
      printf 'deploy: %s rollback failed; check forward-compatible migrations before manual recovery\n' "$service" >&2
      failed=true
    fi
  done
  [[ "$failed" == false ]] || return 1
  rm -- "$PENDING_ENV"
  printf 'deploy: previous committed release restored\n' >&2
}
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ "$status" -ne 0 && -e "$PENDING_ENV" ]]; then
    (recover_pending) || printf 'deploy: recovery journal retained; manual recovery is required\n' >&2
  fi
  [[ -z "$DOCKER_CONFIG" ]] || rm -rf -- "$DOCKER_CONFIG"
  exit "$status"
}
main() {
  local requested_identity='' requested_notification='' candidate_identity candidate_notification
  local identity_changed=false notification_changed=false service iam_token
  [[ "$EUID" -eq 0 ]] || die "run this script as root on the production VM"
  # Retain the original invocation for existing operator scripts.
  if [[ $# -eq 1 && "$1" == cr.yandex/* ]]; then set -- --identity "$1"; fi
  while [[ $# -gt 0 ]]; do
    [[ $# -ge 2 ]] || die "usage: $0 [--identity DIGEST] [--notification DIGEST]"
    case "$1" in
      --identity) [[ -z "$requested_identity" ]] || die "duplicate --identity"; requested_identity="$2" ;;
      --notification) [[ -z "$requested_notification" ]] || die "duplicate --notification"; requested_notification="$2" ;;
      *) die "unknown deployment option: $1" ;;
    esac
    shift 2
  done
  [[ -n "$requested_identity" || -n "$requested_notification" ]] || die "select at least one service image"
  for service in curl jq docker flock mktemp sed cmp stat; do require_command "$service"; done
  docker compose version >/dev/null 2>&1 || die "Docker Compose plugin is unavailable"
  require_private_file "$CONFIG_ENV" 0
  set -a
  # Root-owned static configuration is trusted. Release manifests are never sourced.
  source "$CONFIG_ENV"
  set +a
  [[ "${YC_REGISTRY_ID:-}" =~ ^[a-z0-9]{6,64}$ ]] || die "invalid YC_REGISTRY_ID"
  [[ "${COOKIE_DATA_ROOT:-}" == /srv/cookie || "${COOKIE_DATA_ROOT:-}" == /srv/cookie/* ]] || die "COOKIE_DATA_ROOT must be under /srv/cookie"
  [[ "${COOKIE_SECRET_ROOT:-}" == /srv/cookie/* ]] || die "COOKIE_SECRET_ROOT must be under /srv/cookie"
  [[ "${COOKIE_IDENTITY_ISSUER:-}" == https://* && "${COOKIE_IDENTITY_JWKS_URL:-}" == https://* ]] || die "issuer and JWKS must use HTTPS"
  [[ "${COOKIE_NOTIFICATION_VERIFICATION_LINK_BASE_URL:-}" == https://* ]] || die "verification link must use HTTPS"
  for service in "${COOKIE_IDENTITY_BIND_IP:-}" "${COOKIE_NOTIFICATION_BIND_IP:-}"; do
    [[ "$service" == 127.0.0.1 || "$service" == 0.0.0.0 ]] || die "application binds must be 127.0.0.1 or 0.0.0.0"
  done
  if [[ "${COOKIE_IDENTITY_BIND_IP}" == 0.0.0.0 && -n "${COOKIE_IDENTITY_TRUSTED_PROXY_CIDRS:-}" && "${COOKIE_IDENTITY_TRUSTED_PROXY_CIDRS}" != 198.19.0.0/16 ]]; then
    die "trusted proxy CIDRs must be empty or exactly 198.19.0.0/16"
  fi
  [[ "$HEALTH_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || die "health timeout must be positive"
  [[ -z "$requested_identity" ]] || validate_image identity "$requested_identity"
  [[ -z "$requested_notification" ]] || validate_image notification "$requested_notification"
  require_protected_directory "${COOKIE_DATA_ROOT}/postgres" 70
  require_protected_directory "${COOKIE_DATA_ROOT}/nats" 1000
  case "${COOKIE_FCM_ENABLED:-false}" in
    true|false) ;;
    *) die "COOKIE_FCM_ENABLED must be true or false" ;;
  esac
  validate_secret_layout "$COOKIE_SECRET_ROOT"
  mkdir -p -- "$(dirname "$LOCK_FILE")"
  exec 9>"$LOCK_FILE"
  flock --nonblock 9 || die "another production deployment is already running"
  mkdir -p -- "$RELEASE_DIR"
  chmod 0700 "$RELEASE_DIR"
  require_protected_directory "$RELEASE_DIR" 0
  read_release
  candidate_identity="${requested_identity:-$CURRENT_IDENTITY}"
  candidate_notification="${requested_notification:-$CURRENT_NOTIFICATION}"
  [[ -n "$candidate_identity" && -n "$candidate_notification" ]] \
    || die "first two-service deployment requires both --identity and --notification digests"
  DOCKER_CONFIG="$(mktemp -d /tmp/cookie-docker-config.XXXXXX)"
  chmod 0700 "$DOCKER_CONFIG"
  export DOCKER_CONFIG
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  iam_token="$(curl --silent --show-error --fail --max-time 5 --header 'Metadata-Flavor: Google' \
    'http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token' | jq --exit-status --raw-output '.access_token // empty')"
  [[ -n "$iam_token" ]] || die "VM metadata did not return an IAM token"
  printf '%s' "$iam_token" | docker login --username iam --password-stdin cr.yandex >/dev/null
  unset iam_token
  recover_pending || die "previous interrupted deployment could not be recovered"
  read_release
  export IDENTITY_IMAGE="$candidate_identity" NOTIFICATION_IMAGE="$candidate_notification"
  [[ -z "$requested_identity" && "$IDENTITY_IMAGE" == "$CURRENT_IDENTITY" ]] || identity_changed=true
  [[ -z "$requested_notification" && "$NOTIFICATION_IMAGE" == "$CURRENT_NOTIFICATION" ]] || notification_changed=true
  docker pull "$IDENTITY_IMAGE"
  docker pull "$NOTIFICATION_IMAGE"
  compose config --quiet
  compose pull postgres nats nats-init redis notification-db-provision
  compose up --detach --pull never postgres nats
  wait_for_healthy postgres "$HEALTH_TIMEOUT_SECONDS" || die "PostgreSQL did not become healthy"
  wait_for_healthy nats "$HEALTH_TIMEOUT_SECONDS" || die "NATS did not become healthy"
  compose run --rm --no-deps notification-db-provision
  compose run --rm --no-deps nats-init
  # Redis loss is an explicitly supported degraded mode with PostgreSQL fallback.
  if ! compose up --detach --pull never redis || ! wait_for_healthy redis "$HEALTH_TIMEOUT_SECONDS"; then
    printf 'deploy: Redis unavailable; Notification uses PostgreSQL deadline fallback\n' >&2
  fi
  PENDING_ATTEMPTED=''
  write_pending
  for service in identity notification; do
    if [[ "$service" == identity && "$identity_changed" == false ]]; then continue; fi
    if [[ "$service" == notification && "$notification_changed" == false ]]; then continue; fi
    PENDING_ATTEMPTED="${PENDING_ATTEMPTED:+${PENDING_ATTEMPTED},}${service}"
    write_pending
    upgrade_service "$service" || die "$service migration or startup failed"
    wait_for_healthy "$service" "$HEALTH_TIMEOUT_SECONDS" || die "$service failed readiness"
    wait_for_published "$service" || die "$service published port failed readiness"
  done
  write_release
  rm -- "$PENDING_ENV"
  printf 'deploy: healthy release recorded in %s\n' "$RELEASE_ENV"
}
main "$@"
