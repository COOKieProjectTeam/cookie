# HTTP contracts and generation

## Contract layers

| Contract | Scope | Owner |
|---|---|---|
| `contracts/openapi/openapi.yaml` | Public application API | Caddy routes to tag owner |
| `contracts/openapi/runtime.yaml` | Per-component liveness/readiness | Each deployable component |
| Future internal service specs | Whitelisted synchronous calls | Callee service |
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
- internal Kotlin/JVM clients after internal specs are introduced.

Handwritten:

- handler implementations/adapters;
- application use cases and domain models;
- database and NATS behavior;
- mapping between transport and domain models;
- authorization policy beyond generated route metadata.

Generated code is disposable build output. Never patch it to fix behavior;
change OpenAPI, generator configuration or templates and regenerate.

## Required CI gates

1. Parse and validate every OpenAPI document.
2. Require globally unique and stable `operationId` values per document.
3. Resolve every local `$ref`.
4. Verify public tags have exactly one owner.
5. Run all generation targets using a pinned generator version.
6. Compile generated server interfaces and clients.
7. Fail if regeneration changes tracked files or generated contract tests fail.
