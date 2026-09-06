#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
detector="${script_dir}/detect-ci-scope.sh"

case_output=""

classify() {
  case_output="$(
    printf '%s\0' "$@" |
      env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY bash "$detector" --paths-from-stdin
  )"
}

expect_flag() {
  local flag="$1"
  local expected="$2"
  local actual
  actual="$(printf '%s\n' "$case_output" | awk -F= -v key="$flag" '$1 == key { print $2 }')"
  if [[ "$actual" != "$expected" ]]; then
    echo "expected ${flag}=${expected}, got ${actual:-<missing>}" >&2
    echo "$case_output" >&2
    exit 1
  fi
}

classify backend/services/identity/application/src/main/kotlin/example.kt
expect_flag identity true
expect_flag notification_sink false
expect_flag platform false
expect_flag public_client false
expect_flag mobile false
expect_flag identity_image true
expect_flag identity_container true

classify backend/services/identity/application/src/test/kotlin/example.kt
expect_flag identity true
expect_flag identity_image false

classify backend/platform/starter-messaging/src/main/kotlin/example.kt
expect_flag platform true
expect_flag platform_messaging true
expect_flag platform_web false
expect_flag identity true
expect_flag notification_sink true
expect_flag public_client false
expect_flag mobile false
expect_flag identity_image true

classify backend/platform/starter-testing/src/main/kotlin/example.kt
expect_flag platform true
expect_flag platform_web true
expect_flag platform_postgres false
expect_flag platform_messaging true
expect_flag platform_testing true
expect_flag identity true
expect_flag notification_sink true
expect_flag identity_image false

classify \
  backend/platform/starter-messaging/src/test/kotlin/example.kt \
  backend/platform/starter-web/README.md
expect_flag platform true
expect_flag platform_web false
expect_flag platform_messaging true
expect_flag identity false
expect_flag notification_sink false
expect_flag identity_image false

classify backend/platform/starter-testing/src/test/kotlin/example.kt
expect_flag platform true
expect_flag platform_web false
expect_flag platform_messaging false
expect_flag platform_testing true
expect_flag identity false
expect_flag notification_sink false

classify contracts/openapi/public/identity.yaml
expect_flag service_contracts true
expect_flag identity true
expect_flag public_client true
expect_flag mobile false
expect_flag planned_openapi false
expect_flag identity_image true

classify contracts/openapi/planned.yaml
expect_flag service_contracts false
expect_flag identity false
expect_flag public_client false
expect_flag mobile false
expect_flag planned_openapi true

for source_set in commonMain commonTest jvmMain jvmTest; do
  classify "apps/mobile/shared/src/${source_set}/kotlin/example.kt"
  expect_flag mobile true
  expect_flag public_client false
  expect_flag identity false
done

classify apps/mobile/README.md
expect_flag mobile false

classify backend/services/identity/README.md
expect_flag identity false

classify backend/services/identity/src/main/resources/template.md
expect_flag identity true
expect_flag identity_image true

for resource_name in README.md AGENTS.md CLAUDE.md; do
  classify "backend/services/identity/src/main/resources/${resource_name}"
  expect_flag identity true
  expect_flag identity_image true
  expect_flag identity_container true
done

classify deploy/production/README.md
expect_flag compose false

classify infra/terraform/environments/production/main.tf
expect_flag terraform true
expect_flag compose false
expect_flag identity false

classify deploy/docker/identity.Dockerfile
expect_flag compose true
expect_flag identity_image true
expect_flag identity_container true

classify .dockerignore
expect_flag compose true
expect_flag identity_image true

classify docs/adr/README.md
for flag in \
  go_api platform identity notification_sink service_contracts planned_openapi \
  public_client mobile compose terraform identity_image identity_container; do
  expect_flag "$flag" false
done

classify .github/workflows/ci.yml
for flag in \
  go_api platform identity notification_sink service_contracts planned_openapi \
  public_client mobile compose terraform; do
  expect_flag "$flag" true
done
expect_flag identity_image false
expect_flag identity_container true

if printf '%s\0' backend/services/new-service/src/main/kotlin/example.kt |
  env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY bash "$detector" --paths-from-stdin >/dev/null 2>&1; then
  echo "an unmapped backend service unexpectedly passed change detection" >&2
  exit 1
fi

for unmapped_path in \
  apps/mobile/shared/src/androidMain/kotlin/example.kt \
  apps/mobile/shared/src/androidUnitTest/kotlin/example.kt \
  apps/mobile/shared/src/androidDebug/kotlin/example.kt \
  apps/mobile/shared/src/iosMain/kotlin/example.kt \
  apps/mobile/shared/src/iosTest/kotlin/example.kt \
  apps/mobile/shared/src/iosArm64Main/kotlin/example.kt \
  apps/mobile/shared/src/iosSimulatorArm64Main/kotlin/example.kt \
  apps/mobile/shared/src/newTargetMain/kotlin/example.kt \
  apps/mobile/shared/src/iosMain/resources/README.md \
  apps/mobile/androidApp/src/main/kotlin/example.kt \
  apps/mobile/iosApp/example.swift \
  backend/clients/new-service/src/main/kotlin/example.kt \
  contracts/openapi/internal/new-service.yaml \
  contracts/openapi/generation.yaml \
  deploy/new-environment/compose.yaml \
  infra/terraform/environments/new-environment/main.tf \
  infra/terraform/root.tf; do
  if printf '%s\0' "$unmapped_path" |
    env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY bash "$detector" --paths-from-stdin >/dev/null 2>&1; then
    echo "an unmapped path unexpectedly passed change detection: ${unmapped_path}" >&2
    exit 1
  fi
done

classify deploy/docker/new-service.Dockerfile
expect_flag compose true
expect_flag identity_image false

echo "CI scope mapping tests passed."
