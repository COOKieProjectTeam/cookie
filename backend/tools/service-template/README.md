# Kotlin service scaffold

Run `python3 backend/tools/service-template/create-service.py <service_id> <destination>`.
The output reproduces the verified domain/application/adapter Gradle split,
public/runtime OpenAPI generation, separate integration task and runnable entrypoint.
It contains no Identity business code and never overwrites an existing directory.

The generated README lists the required ownership, contract, migration and CI work
before adding the modules to the build. The canonical service model must already
describe the service; scaffolding does not create or silently authorize service edges.
Notification is the first consumer implementation of this structure.

Before implementation, record the service's important invariants and planned
verification evidence in its README or a linked maintained document. Re-evaluate
them when the architecture or providers change.

Before opening a PR, apply the repository
[service-review workflow](../../../.agents/skills/service-review/SKILL.md).
Review the entire affected flow, including unchanged schemas, locks, permissions
and contracts, and report what was tested and not tested on the reviewed commit.
