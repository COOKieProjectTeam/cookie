# Kotlin service scaffold

Run `python3 backend/tools/service-template/create-service.py <service_id> <destination>`.
The output reproduces the verified domain/application/adapter Gradle split,
public/runtime OpenAPI generation, separate integration task and runnable entrypoint.
It contains no Identity business code and never overwrites an existing directory.

The generated README lists the required ownership, contract, migration and CI work
before adding the modules to the build. The canonical service model must already
describe the service; scaffolding does not create or silently authorize service edges.
Notification is the first consumer implementation of this structure.
