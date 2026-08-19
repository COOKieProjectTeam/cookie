# Agent instructions — API contracts

- Keep OpenAPI valid and reviewable; avoid generated noise.
- Operation IDs and schema names are stable public identifiers.
- Generate Kotlin server transport interfaces/models and HTTP clients from
  OpenAPI according to `openapi/generation.yaml`.
- Do not edit generated sources. Change the contract or generator templates and
  regenerate instead.
- Generated server code stops at the transport boundary; domain use cases and
  handler implementations remain handwritten.
- Internal OpenAPI contracts are owned by the callee and generate one shared
  Kotlin/JVM client module per callee. Do not create aggregate or per-caller
  implementations.
- Every internal operation declares workload authentication, a stable permission
  and user-context mode, and is backed by callee policy plus `sync-calls.yaml`.
- The `health` tag belongs to Health Data Service domain operations. Operational
  liveness/readiness use the `system` tag and `runtime.yaml`.
- Mark breaking changes explicitly in the pull request and coordinate client
  migration in the same change when possible.
- Examples must contain synthetic data only.
- Update the server implementation and contract tests together.
- CI must validate OpenAPI, require unique `operationId` values, ensure every
  public tag has an owner and fail when regeneration produces a diff.
