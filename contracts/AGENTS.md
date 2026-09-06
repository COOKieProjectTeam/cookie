# Agent instructions — API contracts

- Keep OpenAPI valid and reviewable; avoid generated noise.
- Every implemented public service owns exactly one source contract at
  `openapi/public/<service-id>.yaml`. Add a contract to that directory only with
  a compiling handler implementation. `openapi/planned.yaml` is non-active
  roadmap material and must not be used for server or client generation.
- Generate the mobile/gateway bundle from active per-service contracts. Never
  maintain a second handwritten aggregate public contract.
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
- CI must validate every active service contract and the generated bundle,
  require globally unique `operationId` values, ensure every public tag has an
  owner and fail when regeneration produces a diff.
