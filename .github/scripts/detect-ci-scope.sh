#!/usr/bin/env bash

set -Eeuo pipefail

explicit_paths=false
if [[ "${1:-}" == "--paths-from-stdin" && "$#" -eq 1 ]]; then
  explicit_paths=true
elif [[ "$#" -eq 3 ]]; then
  base_sha="$1"
  head_sha="$2"
  event_name="$3"

  case "$event_name" in
    pull_request | push) ;;
    *)
      echo "unsupported GitHub event: ${event_name}" >&2
      exit 2
      ;;
  esac

  if ! git cat-file -e "${head_sha}^{commit}" 2>/dev/null; then
    echo "head commit is unavailable: ${head_sha}" >&2
    exit 1
  fi

  if [[ "$base_sha" =~ ^0+$ ]]; then
    diff_base="$(git hash-object -t tree /dev/null)"
  elif ! git cat-file -e "${base_sha}^{commit}" 2>/dev/null; then
    echo "base commit is unavailable: ${base_sha}" >&2
    exit 1
  elif [[ "$event_name" == "pull_request" ]]; then
    diff_base="$(git merge-base "$base_sha" "$head_sha")"
  else
    diff_base="$base_sha"
  fi
else
  echo "usage: $0 <base-sha> <head-sha> <pull_request|push>" >&2
  echo "       $0 --paths-from-stdin  # NUL-delimited test input" >&2
  exit 2
fi

go_api=false
platform=false
platform_web=false
platform_postgres=false
platform_messaging=false
platform_testing=false
identity=false
notification_sink=false
service_contracts=false
planned_openapi=false
public_client=false
mobile=false
compose=false
terraform=false
identity_image=false
identity_container=false
unmapped_paths=()
changed_count=0

enable_all_gradle_checks() {
  platform=true
  platform_web=true
  platform_postgres=true
  platform_messaging=true
  platform_testing=true
  identity=true
  notification_sink=true
  service_contracts=true
  planned_openapi=true
  public_client=true
  mobile=true
}

enable_all_checks() {
  go_api=true
  enable_all_gradle_checks
  compose=true
  terraform=true
  identity_container=true
}

if [[ "$explicit_paths" == "true" ]]; then
  changed_paths_file=/dev/stdin
else
  changed_paths_file="$(mktemp)"
  cleanup() {
    rm -f -- "$changed_paths_file"
  }
  trap cleanup EXIT
  git diff --name-only --no-renames -z "$diff_base" "$head_sha" > "$changed_paths_file"
fi

while IFS= read -r -d '' path; do
  ((changed_count += 1))
  unmapped_component=false

  case "$path" in
    */src/*)
      ;;
    README.md | */README.md | AGENTS.md | */AGENTS.md | CLAUDE.md | */CLAUDE.md)
      continue
      ;;
  esac

  case "$path" in
    .github/workflows/* | .github/actions/* | .github/scripts/*)
      # CI implementation changes exercise every gate so a broken selector cannot
      # silently turn required checks into no-ops.
      enable_all_checks
      ;;
  esac

  case "$path" in
    .github/workflows/publish-service-image.yml)
      identity_image=true
      ;;
    apps/api/* | go.work | go.work.sum)
      go_api=true
      ;;
    apps/mobile/shared/src/commonMain/* | apps/mobile/shared/src/commonTest/* | \
      apps/mobile/shared/src/jvmMain/* | apps/mobile/shared/src/jvmTest/*)
      mobile=true
      ;;
    apps/mobile/shared/src/*)
      unmapped_component=true
      ;;
    apps/mobile/shared/*)
      mobile=true
      ;;
    apps/mobile/*)
      unmapped_component=true
      ;;
    apps/*/*)
      case "$path" in
        apps/api/* | apps/mobile/*) ;;
        *) unmapped_component=true ;;
      esac
      ;;
  esac

  case "$path" in
    build.gradle.kts | settings.gradle.kts | gradle.properties | gradlew | gradlew.bat | gradle/*)
      enable_all_gradle_checks
      identity_image=true
      ;;
    build-logic/build.gradle.kts | build-logic/settings.gradle.kts)
      enable_all_gradle_checks
      identity_image=true
      ;;
    build-logic/*/CookieSpringServicePlugin.kt)
      identity=true
      notification_sink=true
      identity_image=true
      ;;
    build-logic/*/CookieKotlinLibraryPlugin.kt)
      platform=true
      platform_web=true
      platform_postgres=true
      platform_messaging=true
      platform_testing=true
      identity=true
      notification_sink=true
      identity_image=true
      ;;
    build-logic/*/CookieKotlinMultiplatformPlugin.kt)
      mobile=true
      ;;
    build-logic/*)
      # Unknown convention-plugin changes are treated as common Gradle changes.
      enable_all_gradle_checks
      identity_image=true
      ;;
  esac

  case "$path" in
    backend/platform/starter-web/*)
      case "$path" in
        */src/test/*)
          platform=true
          platform_web=true
          ;;
        */src/main/* | */build.gradle.kts)
          platform=true
          platform_web=true
          identity=true
          identity_image=true
          ;;
        *)
          platform=true
          platform_web=true
          identity=true
          identity_image=true
          ;;
      esac
      ;;
    backend/platform/starter-postgres/*)
      case "$path" in
        */src/test/*)
          platform=true
          platform_postgres=true
          ;;
        */src/main/* | */build.gradle.kts)
          platform=true
          platform_postgres=true
          identity=true
          identity_image=true
          ;;
        *)
          platform=true
          platform_postgres=true
          identity=true
          identity_image=true
          ;;
      esac
      ;;
    backend/platform/starter-messaging/*)
      case "$path" in
        */src/test/*)
          platform=true
          platform_messaging=true
          ;;
        */src/main/* | */build.gradle.kts)
          platform=true
          platform_messaging=true
          identity=true
          notification_sink=true
          identity_image=true
          ;;
        *)
          platform=true
          platform_messaging=true
          identity=true
          notification_sink=true
          identity_image=true
          ;;
      esac
      ;;
    backend/platform/starter-testing/*)
      case "$path" in
        */src/test/*)
          platform=true
          platform_testing=true
          ;;
        */src/main/* | */build.gradle.kts)
          # These consumers compile their tests against starter-testing.
          platform=true
          platform_testing=true
          platform_web=true
          platform_messaging=true
          identity=true
          notification_sink=true
          ;;
        *)
          platform=true
          platform_testing=true
          platform_web=true
          platform_messaging=true
          identity=true
          notification_sink=true
          ;;
      esac
      ;;
    backend/platform/*/*)
      unmapped_component=true
      ;;
  esac

  case "$path" in
    backend/services/identity/service.yaml)
      service_contracts=true
      ;;
    backend/services/identity/*)
      identity=true
      case "$path" in
        */src/main/* | */build.gradle.kts)
          identity_image=true
          ;;
      esac
      ;;
    backend/services/*/*)
      # A new service must declare its own path-to-test mapping before it can merge.
      unmapped_component=true
      ;;
    backend/tools/notification-sink/*)
      notification_sink=true
      ;;
    backend/tools/*/*)
      unmapped_component=true
      ;;
  esac

  case "$path" in
    contracts/openapi/public/identity.yaml)
      service_contracts=true
      identity=true
      public_client=true
      identity_image=true
      ;;
    contracts/openapi/public/*)
      service_contracts=true
      public_client=true
      ;;
    contracts/openapi/runtime.yaml)
      service_contracts=true
      identity=true
      identity_image=true
      ;;
    contracts/openapi/planned.yaml)
      planned_openapi=true
      ;;
    contracts/openapi/generation.yaml)
      service_contracts=true
      identity=true
      public_client=true
      identity_image=true
      ;;
    contracts/openapi/openapi.yaml)
      if [[ "$explicit_paths" == "false" ]] \
        && git cat-file -e "${diff_base}:${path}" 2>/dev/null \
        && ! git cat-file -e "${head_sha}:${path}" 2>/dev/null; then
        service_contracts=true
        planned_openapi=true
        public_client=true
      else
        unmapped_component=true
      fi
      ;;
    contracts/openapi/internal/*.yaml)
      # These files do not have executable validators yet; never report a false green.
      unmapped_component=true
      ;;
    contracts/openapi/*)
      unmapped_component=true
      ;;
    docs/architecture/model/services.yaml)
      service_contracts=true
      public_client=true
      ;;
    docs/architecture/model/events.yaml)
      service_contracts=true
      ;;
  esac

  case "$path" in
    backend/clients/*)
      unmapped_component=true
      ;;
  esac

  case "$path" in
    .dockerignore | Makefile | deploy/docker/* | deploy/production/*)
      compose=true
      ;;
  esac

  case "$path" in
    .dockerignore | deploy/docker/identity.Dockerfile)
      identity_image=true
      ;;
  esac

  case "$path" in
    deploy/*)
      case "$path" in
        deploy/docker/* | deploy/production/*) ;;
        *) unmapped_component=true ;;
      esac
      ;;
  esac

  case "$path" in
    infra/terraform/environments/dev/* | \
      infra/terraform/environments/production/* | \
      infra/terraform/modules/naming/*)
      terraform=true
      ;;
    infra/terraform/*)
      unmapped_component=true
      ;;
  esac

  if [[ "$unmapped_component" == "true" ]]; then
    unmapped_paths+=("$path")
  fi
done < "$changed_paths_file"

if [[ "$identity_image" == "true" ]]; then
  identity_container=true
fi

emit_output() {
  local output_name="$1"
  local output_value="$2"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "$output_name" "$output_value" >> "$GITHUB_OUTPUT"
  else
    printf '%s=%s\n' "$output_name" "$output_value"
  fi
}

emit_output go_api "$go_api"
emit_output platform "$platform"
emit_output platform_web "$platform_web"
emit_output platform_postgres "$platform_postgres"
emit_output platform_messaging "$platform_messaging"
emit_output platform_testing "$platform_testing"
emit_output identity "$identity"
emit_output notification_sink "$notification_sink"
emit_output service_contracts "$service_contracts"
emit_output planned_openapi "$planned_openapi"
emit_output public_client "$public_client"
emit_output mobile "$mobile"
emit_output compose "$compose"
emit_output terraform "$terraform"
emit_output identity_image "$identity_image"
emit_output identity_container "$identity_container"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "### CI scope"
    echo
    echo "Changed paths: ${changed_count}"
    echo
    echo '| Target | Run |'
    echo '| --- | --- |'
    printf '| %s | %s |\n' go_api "$go_api"
    printf '| %s | %s |\n' platform "$platform"
    printf '| %s | %s |\n' identity "$identity"
    printf '| %s | %s |\n' notification_sink "$notification_sink"
    printf '| %s | %s |\n' service_contracts "$service_contracts"
    printf '| %s | %s |\n' planned_openapi "$planned_openapi"
    printf '| %s | %s |\n' public_client "$public_client"
    printf '| %s | %s |\n' mobile "$mobile"
    printf '| %s | %s |\n' compose "$compose"
    printf '| %s | %s |\n' terraform "$terraform"
    printf '| %s | %s |\n' identity_image "$identity_image"
    printf '| %s | %s |\n' identity_container "$identity_container"
  } >> "$GITHUB_STEP_SUMMARY"
fi

if (( ${#unmapped_paths[@]} > 0 )); then
  echo "::error::Changed paths have no CI mapping. Update .github/scripts/detect-ci-scope.sh." >&2
  for path in "${unmapped_paths[@]}"; do
    printf 'Unmapped CI path: %q\n' "$path" >&2
  done
  exit 1
fi
