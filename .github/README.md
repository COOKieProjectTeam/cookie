# GitHub CI configuration

`CI` runs for every pull request targeting `main`. The `Detect changes` job maps
changed paths to the smallest safe set of checks; service jobs that are not
affected are skipped. `CI required` always runs and fails when change detection
or any selected check fails or is cancelled.

Configure the repository ruleset for `main` with:

- pull requests required before merge;
- the status check `CI required` from the `CI` workflow required (the GitHub UI
  may display it as `CI / CI required`);
- branch required to be up to date before merge;
- bypass disabled except for an explicitly documented emergency role.

Do not mark the conditional service jobs as required: GitHub cannot know which
ones should exist for a particular diff. The stable aggregate check is the only
required status check. Apply the ruleset after this workflow has run once so the
check is available in GitHub's selector.

## Change mapping

| Changed area | Checks selected |
| --- | --- |
| `apps/api/**` | Legacy Go API |
| Identity source, tests, or Gradle files | Identity only |
| `backend/services/identity/service.yaml` | Service contracts |
| Notification sink source, tests, or Gradle files | Notification sink only |
| `starter-web`/`starter-postgres` main source or Gradle file | Changed platform module and Identity |
| `starter-messaging` main source or Gradle file | Messaging platform, Identity, Notification sink |
| `starter-testing` main source or Gradle file | Test-dependent platform modules, Identity, Notification sink |
| Any platform module's own `src/test/**` | Only that platform module |
| `contracts/openapi/public/identity.yaml` | Contracts, Identity, generated-client compatibility smoke |
| `contracts/openapi/runtime.yaml` | Contracts and Identity |
| `contracts/openapi/generation.yaml` | Generation configuration validation, Identity, generated-client compatibility and Identity image build |
| Deleted legacy `contracts/openapi/openapi.yaml` | Contracts, planned OpenAPI and generated-client compatibility; deletion must be confirmed against the Git base/head trees |
| `apps/mobile/shared/src/commonMain/**`, `commonTest/**`, `jvmMain/**`, `jvmTest/**`, mobile build files | Configured Mobile JVM/shared checks |
| Supported `deploy/docker/**`, `deploy/production/**`, `.dockerignore`, `Makefile` | Compose/deployment bundle |
| Terraform dev, production, or naming-module implementation | Terraform only |
| Repository docs and component `README.md`/`AGENTS.md` files outside `src/**` | No component tests; aggregate gate only |

A public OpenAPI change compiles the generated KMP client because that contract
is its actual source, but it does not run the direct Mobile job. An ordinary
backend implementation change never starts either client job. Shared
Gradle/build-logic or CI-selector changes deliberately run every
potentially affected check. On pull requests, a changed production Identity
image input is also built fully without publishing; the merge-to-`main` run then
publishes that image through the reusable per-service workflow. New application,
backend service, platform module, backend client, backend tool, deployment area,
or Terraform environment paths fail change detection until their mapping is
added, which prevents a new component from silently bypassing CI.

Only `commonMain`, `commonTest`, `jvmMain` and `jvmTest` are configured in the
Mobile Gradle build. Every other source set, including Android and iOS
placeholders, fails scope detection. Add the target and update the mapping in
the same pull request when either platform is activated. Files under `src/**`
remain build inputs even when named `README.md`, `AGENTS.md` or `CLAUDE.md`.
