# Architecture Decision Records

ADR фиксируют долгоживущие технические решения. Имя файла:
`NNNN-short-title.md`. Статусы: `proposed`, `accepted`, `superseded`.

## Index

- `0001-repository-boundaries.md` — границы code/product/labs repositories.
- `0002-kotlin-multiplatform-client.md` — общий Android/iOS client.
- `0003-kotlin-backend.md` — Kotlin/JVM backend services.
- `0004-transactional-messaging.md` — NATS JetStream, outbox и inbox.
- `0005-openapi-code-generation.md` — contract-first handlers and clients.
- `0006-kotlin-service-platform.md` — единый production-ready путь для Kotlin-сервисов.
- `0007-synchronous-service-access.md` — per-callee clients и service-to-service access control.
- `0008-identity-service-pilot.md` — JVM/framework/toolchain и первый production vertical slice.
- `0009-identity-security.md` — email/password policy, tokens, keys and encrypted email events.
- `0010-per-service-public-openapi.md` — service-owned public contracts and generated active bundle.
- `0011-account-deletion-lifecycle.md` — asynchronous account deletion, immediate access closure and staged distributed cleanup.
- `0012-yandex-single-environment-deployment.md` — local dev, один минимальный Yandex production и per-service image delivery.
