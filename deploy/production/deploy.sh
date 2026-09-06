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
  (( (8#${mode} & 077) == 0 )) \
    || die "secret file must not be accessible by group/other: ${path}"
}

require_protected_directory() {
  local path="$1"
  local expected_uid="$2"
  local mode

  [[ -d "${path}" && ! -L "${path}" ]] \
    || die "protected path must be a real directory, not a symlink: ${path}"
  require_owner "${path}" "${expected_uid}"
  mode="$(stat --format '%a' "${path}")"
  (( (8#${mode} & 022) == 0 )) \
    || die "protected directory must not be writable by group/other: ${path}"
}

write_release() {
  local image="$1"
  local temporary

  mkdir -p -- "${RELEASE_DIR}"
  temporary="$(mktemp "${RELEASE_DIR}/current.env.tmp.XXXXXX")"
  printf 'IDENTITY_IMAGE=%s\n' "${image}" >"${temporary}"
  chmod 0600 "${temporary}"
  mv -f -- "${temporary}" "${RELEASE_ENV}"
}

read_current_image() {
  local line

  [[ -r "${RELEASE_ENV}" ]] || return 0
  line="$(sed -n 's/^IDENTITY_IMAGE=//p' "${RELEASE_ENV}")"
  [[ "${line}" != *$'\n'* ]] || die "release file contains multiple image values"
  printf '%s' "${line}"
}

compose() {
  docker compose \
    --project-directory "${BUNDLE_DIR}" \
    --env-file "${CONFIG_ENV}" \
    --file "${COMPOSE_FILE}" \
    "$@"
}

container_health() {
  local service="$1"
  local container_id

  container_id="$(compose ps --quiet "${service}")"
  [[ -n "${container_id}" ]] || return 1
  docker inspect \
    --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
    "${container_id}"
}

wait_for_healthy() {
  local service="$1"
  local timeout="$2"
  local deadline=$((SECONDS + timeout))
  local status=""

  while (( SECONDS < deadline )); do
    status="$(container_health "${service}" 2>/dev/null || true)"
    case "${status}" in
      healthy)
        return 0
        ;;
      exited|dead)
        return 1
        ;;
    esac
    sleep 2
  done

  printf 'deploy: timed out waiting for %s (last status: %s)\n' \
    "${service}" "${status:-not-created}" >&2
  return 1
}

wait_for_published_identity() {
  local timeout="$1"
  local deadline=$((SECONDS + timeout))
  local http_code=""

  while (( SECONDS < deadline )); do
    http_code="$(
      curl --silent --output /dev/null --write-out '%{http_code}' --max-time 3 \
        'http://127.0.0.1:8080/readyz' 2>/dev/null || true
    )"
    if [[ "${http_code}" == 200 ]]; then
      return 0
    fi
    sleep 2
  done

  printf 'deploy: published Identity port did not become ready (last HTTP status: %s)\n' \
    "${http_code:-unreachable}" >&2
  return 1
}

validate_secret_layout() {
  local secret_root="$1"
  local required_files=(
    "${secret_root}/postgres/admin-password"
    "${secret_root}/postgres/identity-password"
    "${secret_root}/postgres/identity-migration-password"
    "${secret_root}/identity/spring.datasource.password"
    "${secret_root}/identity/spring.flyway.password"
    "${secret_root}/identity/cookie.identity.rate-limit-hmac-key"
    "${secret_root}/identity/cookie.identity.nats-truststore-password"
    "${secret_root}/identity/identity-jwt-private.jwk"
    "${secret_root}/identity/notification-public.jwk"
    "${secret_root}/identity/nats-identity.creds"
    "${secret_root}/identity/nats-truststore.jks"
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

  for path in "${required_files[@]}"; do
    [[ -s "${path}" ]] || die "required secret/material file is missing or empty: ${path}"
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
  require_owner "${secret_root}/nats/ca.crt" 0
  require_owner "${secret_root}/nats/server.crt" 0

  cmp -s \
    "${secret_root}/postgres/identity-password" \
    "${secret_root}/identity/spring.datasource.password" \
    || die "PostgreSQL and Identity runtime-password copies do not match"
  cmp -s \
    "${secret_root}/postgres/identity-migration-password" \
    "${secret_root}/identity/spring.flyway.password" \
    || die "PostgreSQL and Identity migration-password copies do not match"
}

rollback_identity() {
  local previous_image="$1"

  if [[ -z "${previous_image}" ]]; then
    printf 'deploy: no previous release exists; stopping the failed Identity container\n' >&2
    compose stop identity >/dev/null 2>&1 || true
    return 1
  fi

  printf 'deploy: attempting schema-compatible rollback to %s\n' "${previous_image}" >&2
  if ! docker image inspect "${previous_image}" >/dev/null 2>&1 \
    && ! docker pull "${previous_image}"; then
    printf 'deploy: rollback image is unavailable; manual recovery is required\n' >&2
    return 1
  fi
  export IDENTITY_IMAGE="${previous_image}"
  if ! compose up --detach --no-deps --force-recreate --pull never identity; then
    printf 'deploy: Docker could not restore the previous Identity container\n' >&2
    return 1
  fi

  if wait_for_healthy identity "${HEALTH_TIMEOUT_SECONDS}" \
    && wait_for_published_identity "${PUBLISHED_HEALTH_TIMEOUT_SECONDS}"; then
    printf 'deploy: previous Identity image is healthy again\n' >&2
  else
    printf 'deploy: rollback failed; inspect Identity logs and database migration compatibility\n' >&2
  fi
  return 1
}

main() {
  local candidate_image="${1:-}"
  local expected_prefix
  local previous_image
  local docker_config
  local iam_token

  [[ "${EUID}" -eq 0 ]] || die "run this script as root on the production VM"
  [[ $# -eq 1 ]] || die "usage: $0 cr.yandex/<registry-id>/identity@sha256:<64-hex-digest>"

  for command_name in curl jq docker flock mktemp sed cmp stat; do
    require_command "${command_name}"
  done
  docker compose version >/dev/null 2>&1 || die "Docker Compose plugin is unavailable"

  [[ -r "${CONFIG_ENV}" ]] || die "static environment file is not readable: ${CONFIG_ENV}"
  require_private_file "${CONFIG_ENV}" 0
  set -a
  # This file is root-owned deployment configuration, not user input.
  # shellcheck disable=SC1090
  source "${CONFIG_ENV}"
  set +a

  [[ "${YC_REGISTRY_ID:-}" =~ ^[a-z0-9]{6,64}$ ]] \
    || die "YC_REGISTRY_ID must contain only lowercase letters and digits"
  [[ "${COOKIE_DATA_ROOT:-}" == /* ]] || die "COOKIE_DATA_ROOT must be an absolute path"
  [[ "${COOKIE_SECRET_ROOT:-}" == /* ]] || die "COOKIE_SECRET_ROOT must be an absolute path"
  [[ "${COOKIE_DATA_ROOT}" == /srv/cookie || "${COOKIE_DATA_ROOT}" == /srv/cookie/* ]] \
    || die "COOKIE_DATA_ROOT must be /srv/cookie or a child of it"
  [[ "${COOKIE_SECRET_ROOT}" == /srv/cookie/* ]] \
    || die "COOKIE_SECRET_ROOT must be a child of /srv/cookie"
  [[ "${COOKIE_IDENTITY_ISSUER:-}" == https://* ]] || die "COOKIE_IDENTITY_ISSUER must use HTTPS"
  [[ "${COOKIE_IDENTITY_BIND_IP:-}" == 127.0.0.1 || "${COOKIE_IDENTITY_BIND_IP:-}" == 0.0.0.0 ]] \
    || die "COOKIE_IDENTITY_BIND_IP must be 127.0.0.1 or 0.0.0.0"
  if [[ "${COOKIE_IDENTITY_BIND_IP}" == 0.0.0.0 ]] \
    && [[ -n "${COOKIE_IDENTITY_TRUSTED_PROXY_CIDRS:-}" ]] \
    && [[ "${COOKIE_IDENTITY_TRUSTED_PROXY_CIDRS}" != 198.19.0.0/16 ]]; then
    die "trusted proxy CIDRs must be empty or exactly 198.19.0.0/16 behind API Gateway"
  fi
  [[ "${HEALTH_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]] || die "health timeout must be a positive integer"

  expected_prefix="cr.yandex/${YC_REGISTRY_ID}/identity@sha256:"
  [[ "${candidate_image}" =~ ^cr\.yandex/[a-z0-9]{6,64}/identity@sha256:[a-f0-9]{64}$ ]] \
    || die "Identity image must be an immutable Yandex Registry digest reference"
  [[ "${candidate_image}" == "${expected_prefix}"* ]] \
    || die "Identity image belongs to a different registry than YC_REGISTRY_ID"

  [[ -d "${COOKIE_DATA_ROOT}/postgres" ]] \
    || die "PostgreSQL data directory is missing: ${COOKIE_DATA_ROOT}/postgres"
  [[ -d "${COOKIE_DATA_ROOT}/nats" ]] \
    || die "NATS data directory is missing: ${COOKIE_DATA_ROOT}/nats"
  [[ -d "${COOKIE_SECRET_ROOT}/identity/retiring" ]] \
    || die "Identity retiring-key directory is missing: ${COOKIE_SECRET_ROOT}/identity/retiring"
  require_owner "${COOKIE_DATA_ROOT}/postgres" 70
  require_owner "${COOKIE_DATA_ROOT}/nats" 1000
  require_owner "${COOKIE_SECRET_ROOT}/identity/retiring" 10001
  validate_secret_layout "${COOKIE_SECRET_ROOT}"

  mkdir -p -- "$(dirname -- "${LOCK_FILE}")"
  exec 9>"${LOCK_FILE}"
  flock --nonblock 9 || die "another production deployment is already running"

  docker_config="$(mktemp -d /tmp/cookie-docker-config.XXXXXX)"
  chmod 0700 "${docker_config}"
  export DOCKER_CONFIG="${docker_config}"
  trap 'unset iam_token 2>/dev/null || true; rm -rf -- "${DOCKER_CONFIG:-/tmp/nonexistent-cookie-docker-config}"' EXIT

  iam_token="$(
    curl --silent --show-error --fail --max-time 5 \
      --header 'Metadata-Flavor: Google' \
      'http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token' \
      | jq --exit-status --raw-output '.access_token // empty'
  )"
  [[ -n "${iam_token}" ]] || die "VM metadata did not return an IAM token"
  printf '%s' "${iam_token}" \
    | docker login --username iam --password-stdin cr.yandex >/dev/null
  unset iam_token

  printf 'deploy: pulling immutable Identity image %s\n' "${candidate_image}"
  docker pull "${candidate_image}"
  docker image inspect "${candidate_image}" >/dev/null

  previous_image="$(read_current_image)"
  if [[ -n "${previous_image}" ]] \
    && [[ ! "${previous_image}" =~ ^cr\.yandex/${YC_REGISTRY_ID}/identity@sha256:[a-f0-9]{64}$ ]]; then
    die "current release file contains an invalid Identity image reference"
  fi

  export IDENTITY_IMAGE="${candidate_image}"
  compose pull postgres nats nats-init
  compose up --detach --pull never postgres nats
  wait_for_healthy postgres "${HEALTH_TIMEOUT_SECONDS}" \
    || die "PostgreSQL did not become healthy"
  wait_for_healthy nats "${HEALTH_TIMEOUT_SECONDS}" \
    || die "NATS did not become healthy"

  compose run --rm --no-deps nats-init

  if ! compose up --detach --no-deps --force-recreate --pull never identity; then
    compose logs --tail 100 identity >&2 || true
    rollback_identity "${previous_image}"
  fi

  if ! wait_for_healthy identity "${HEALTH_TIMEOUT_SECONDS}"; then
    compose logs --tail 100 identity >&2 || true
    rollback_identity "${previous_image}"
  fi
  if ! wait_for_published_identity "${PUBLISHED_HEALTH_TIMEOUT_SECONDS}"; then
    compose logs --tail 100 identity >&2 || true
    rollback_identity "${previous_image}"
  fi

  write_release "${candidate_image}"
  printf 'deploy: Identity is healthy at %s\n' "${candidate_image}"
  printf 'deploy: release recorded in %s\n' "${RELEASE_ENV}"
}

main "$@"
