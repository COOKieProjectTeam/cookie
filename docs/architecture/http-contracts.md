# HTTP contracts and generation

## Contract layers

| Contract | Scope | Owner |
|---|---|---|
| `contracts/openapi/public/<service-id>.yaml` | Implemented public API of one service | Deployable service |
| `build/generated/openapi/bundled/public.yaml` | Generated active mobile/gateway API | Repository build |
| `contracts/openapi/planned.yaml` | Non-active API roadmap; never used for generation | Product architecture |
| `contracts/openapi/runtime.yaml` | Operator/orchestrator-only per-component liveness/readiness | Each deployable component |
| `contracts/openapi/internal/<service-id>.yaml` | Whitelisted synchronous calls | Callee service |
| `contracts/openapi/generation.yaml` | Generation policy and targets | Repository build |

The `health` tag means domain health data. The `system` tag means operational
availability. They must never share an owner because their names sound similar.

`/healthz` and `/readyz` are reserved runtime paths. They are deliberately
unauthenticated at the application layer, but this is not public exposure:
deployment networking makes them reachable only to the orchestrator and
operators. Service-owned public OpenAPI contracts, the generated mobile client
and public gateway routing must never contain or proxy these paths. Caddy has
its own component-local probes on an operator/runtime listener; a gateway probe
never forwards to a backend component and is not a product API operation.
Generated public and runtime interfaces currently share the placeholder
`api.base-path`, so deployables must reject that override and use the base paths
compiled from their OpenAPI contracts. Changing this requires separate
generator properties/templates, not a global runtime prefix.

## Per-service ownership and bundle

Each implemented service owns one public source contract named by `service-id`.
Adding a file to `contracts/openapi/public/` requires a compiling generated
server interface and handwritten handler in that service. Future operations stay
in `planned.yaml` and cannot appear in generated clients.

`bundlePublicOpenApi` merges active service contracts into disposable build
output. It rejects duplicate routes and conflicting tags, components, server
definitions or top-level security defaults. The KMP client and gateway-facing
validation consume this generated bundle, never the roadmap contract.

Public operations remain assigned by `openapi_tags` in `model/services.yaml`.
An operation with an unknown tag or multiple conflicting owners fails contract
validation.

## Generated boundary

Generated:

- server transport interfaces and routing contracts;
- request/response serialization models;
- public Kotlin Multiplatform client;
- one internal Kotlin/JVM client module per callee.

Handwritten:

- handler implementations/adapters;
- application use cases and domain models;
- database and NATS behavior;
- mapping between transport and domain models;
- authorization policy beyond generated route metadata.

Generated code is disposable build output. Never patch it to fix behavior;
change OpenAPI, generator configuration or templates and regenerate.

## Internal clients and access

Internal server transport and `backend/clients/<callee>` are generated from the same
callee-owned contract. Callers depend on the shared per-callee module and keep
transport-to-domain mapping in a local adapter. A single aggregate clients
artifact and independent per-caller client implementations are forbidden.

Every internal operation declares workload authentication, stable permission
and user-context mode. Exact caller grants live in the callee service descriptor
and must be backed by `model/sync-calls.yaml`. Full enforcement rules are in
`service-access-control.md`.

## Required CI gates

1. Parse and validate every active per-service OpenAPI document.
2. Generate and validate the active public bundle.
3. Require globally unique and stable `operationId` values in the bundle.
4. Resolve every local `$ref`.
5. Verify public tags have exactly one owner.
6. Run all generation targets using a pinned generator version.
7. Compile generated server interfaces and clients.
8. Fail if regeneration changes tracked files or generated contract tests fail.
9. Verify every internal permission and caller against callee policy and the
   synchronous dependency model.
10. Reject internal or component runtime routes exposed by public gateway
    configuration.
