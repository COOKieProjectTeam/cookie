# HTTP contracts and generation

## Contract layers

| Contract | Scope | Owner |
|---|---|---|
| `contracts/openapi/openapi.yaml` | Public application API | Caddy routes to tag owner |
| `contracts/openapi/runtime.yaml` | Per-component liveness/readiness | Each deployable component |
| `contracts/openapi/internal/<service-id>.yaml` | Whitelisted synchronous calls | Callee service |
| `contracts/openapi/generation.yaml` | Generation policy and targets | Repository build |

The `health` tag means domain health data. The `system` tag means operational
availability. They must never share an owner because their names sound similar.

## Tag ownership

Public operations are assigned by `openapi_tags` in
`model/services.yaml`. An operation with an unknown tag or multiple conflicting
owners fails contract validation.

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

Internal server transport and `clients/<callee>` are generated from the same
callee-owned contract. Callers depend on the shared per-callee module and keep
transport-to-domain mapping in a local adapter. A single aggregate clients
artifact and independent per-caller client implementations are forbidden.

Every internal operation declares workload authentication, stable permission
and user-context mode. Exact caller grants live in the callee service descriptor
and must be backed by `model/sync-calls.yaml`. Full enforcement rules are in
`service-access-control.md`.

## Required CI gates

1. Parse and validate every OpenAPI document.
2. Require globally unique and stable `operationId` values per document.
3. Resolve every local `$ref`.
4. Verify public tags have exactly one owner.
5. Run all generation targets using a pinned generator version.
6. Compile generated server interfaces and clients.
7. Fail if regeneration changes tracked files or generated contract tests fail.
8. Verify every internal permission and caller against callee policy and the
   synchronous dependency model.
9. Reject internal routes exposed by public gateway configuration.
