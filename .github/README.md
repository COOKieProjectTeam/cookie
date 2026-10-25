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
| Identity main source/build files | Identity and real Identity-to-Notification integration flow |
| Identity test files | Identity only |
| Notification source, tests, or Gradle files | Notification and User process integration; main/build changes also select Nutrition |
| `backend/services/notification/service.yaml` | Service contracts and deployment subject alignment |
| `contracts/openapi/public/notification.yaml` | Contracts, Notification, generated-client compatibility |
| `backend/clients/identity/**` | Notification (JWKS consumer) |
| `backend/tools/service-template/**` | All Gradle convention consumers and template Python syntax |
| `backend/services/identity/service.yaml` | Service contracts |
| Notification sink source, tests, or Gradle files | Notification sink only |
| `starter-web`/`starter-postgres` main source or Gradle file | Changed platform module and descriptor-declared runtime consumers |
| `starter-messaging` main source or Gradle file | Messaging platform, descriptor-declared runtime consumers, Notification sink |
| `starter-testing` main source or Gradle file | Test-dependent platform modules, descriptor-declared test consumers, Notification sink |
| Any platform module's own `src/test/**` | Only that platform module |
| `contracts/openapi/public/identity.yaml` | Contracts, Identity, Notification integration, generated-client compatibility smoke |
| `contracts/openapi/runtime.yaml` | Contracts, Identity and Notification |
| `contracts/openapi/generation.yaml` | Generation configuration validation, Identity, Notification, generated-client compatibility and both image builds |
| Deleted legacy `contracts/openapi/openapi.yaml` | Contracts, planned OpenAPI and generated-client compatibility; deletion must be confirmed against the Git base/head trees |
| `apps/mobile/shared/src/commonMain/**`, `commonTest/**`, `jvmMain/**`, `jvmTest/**`, mobile build files | Configured Mobile JVM/shared checks |
| Supported `deploy/docker/**`, `deploy/production/**`, `.dockerignore`, `Makefile` | Compose/deployment bundle |
| Terraform dev, production, or naming-module implementation | Terraform only |
| Repository docs and component `README.md`/`AGENTS.md` files outside `src/**` | No component tests; aggregate gate only |

A public OpenAPI change compiles the generated KMP client because that contract
is its actual source, but it does not run the direct Mobile job. An ordinary
backend implementation change never starts either client job. Shared
Gradle/build-logic or CI-selector changes deliberately run every
potentially affected check. On pull requests, a changed production service
image input is also built fully without publishing; the merge-to-`main` run then
publishes each selected image through the reusable per-service workflow. New application,
backend service, platform module, backend client, backend tool, deployment area,
or Terraform environment paths fail change detection until their mapping is
added, which prevents a new component from silently bypassing CI.

Service fan-out is declared once in each `backend/services/<id>/service.yaml`
under `ci`. The mapping uses JSON-compatible YAML flow syntax so the first CI
job can read and validate it with Python's standard library, without a package
installation. `check` names an exported workflow job; `image` names its optional
production image flag. `runtime_inputs` select the check and image, while
`test_inputs` select only the check (for example Notification's process JAR is
a User test dependency). `descriptor_outputs` select contract/deployment checks
when the descriptor changes. All paths are repository-relative globs; a
module's own `src/test/**` never affects its platform consumers. Source changes
under a service's own directory automatically select its check; its main/build
changes also select its image, when registered. Documentation outside `src/**`
continues to be ignored.

Adding a service requires one real workflow job/output and its descriptor;
shared-platform branches do not need new per-service assignments. Missing or
malformed metadata, unregistered jobs and omitted direct Gradle project
dependencies fail detection. The selector's tests
include adding a synthetic descriptor, runtime versus test dependencies and
the fail-closed cases. Register image publishing separately when production
deployment is enabled.

Only `commonMain`, `commonTest`, `jvmMain` and `jvmTest` are configured in the
Mobile Gradle build. Every other source set, including Android and iOS
placeholders, fails scope detection. Add the target and update the mapping in
the same pull request when either platform is activated. Files under `src/**`
remain build inputs even when named `README.md`, `AGENTS.md` or `CLAUDE.md`.

Notification has independent service, PR image-build and merge-to-main publish
jobs. `CI required` validates all three selector outputs, and tests-only changes
do not publish an image. Deployment checks run disposable PostgreSQL, Redis and NATS
containers to verify repeatable provisioning on populated databases, application
role isolation, Redis ACL boundaries and durable-consumer permissions. The
fast deployment lifecycle tests use mock tools. A separate mandatory
`Production deployment rehearsal` job builds and loads both real service images
on a disposable Linux runner. It runs the production Compose bundle and
deployment script with synthetic secrets, TLS/JWT NATS, HTTPS JWKS and an
authenticated STARTTLS email sink. It checks fresh installation, an existing
Identity database, independent Notification updates, failed rollout rollback and
recovery after SIGKILL. Only Yandex metadata, registry login and cloud image
references are adapted to local immutable images; containers, permissions,
health checks, locks and migrations execute normally. This job runs when Compose
or either production image is affected. It never deploys to production or uses
real provider credentials.

The always-run `Architecture snapshot and generator` job is included in
`CI required`. It validates the offline HTML explorer against service
descriptors, OpenAPI, architecture YAML and source-linked details, runs
generator/UI regressions, and rejects stale generated documentation.
Regenerate with `make architecture-build`; verify with
`make architecture-check`. Review the handwritten facts and flows when their
referenced implementation changes.

The `User service` job runs User unit and real PostgreSQL/JetStream HTTP integration
checks. User source/contracts and common JVM/platform/CI changes select it, and
`CI required` aggregates its result. User does not yet participate in production
image publication or ingress; creating a workflow does not change branch rulesets.

Nutrition has an independent mandatory job for unit/OpenAPI and real
PostgreSQL/JetStream integration checks. Service files and its public contract
select that job; shared Gradle/CI/platform runtime inputs select it as well.
The actual Notification JAR and generated KMP client are test dependencies:
Notification module sources/build files, active public contracts and KMP inputs
also select Nutrition. The integration task fingerprints the Notification JAR
and runtime-grants script, so dependency changes invalidate cached test results.
`CI required` incorporates its selected result. This registration does not
modify GitHub branch-protection settings.
