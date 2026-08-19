# ADR 0005: генерация HTTP transport-кода из OpenAPI

- Status: accepted
- Date: 2026-08-19

## Context

Публичный API обслуживается несколькими Kotlin/JVM-сервисами и используется
Kotlin Multiplatform-клиентом. Ручное дублирование routes, transport DTO и
клиентов приводит к расхождению контракта, backend и mobile.

## Decision

OpenAPI является единственным источником истины для HTTP transport boundary.
Из него генерируются:

- Kotlin/JVM server interfaces, routing contracts и transport models;
- Kotlin Multiplatform HTTP client и transport models для публичного API;
- Kotlin/JVM clients для разрешённых синхронных межсервисных вызовов, когда для
  них будут выделены внутренние OpenAPI-контракты.

Генерация не создаёт бизнес-логику. Handwritten adapters реализуют generated
server interfaces и вызывают domain/application use cases. Generated transport
models не становятся domain models автоматически.

Generated sources размещаются в Gradle build directories и не коммитятся.
Версия генератора и templates фиксируются. CI валидирует contracts, уникальность
`operationId`, ownership tags и проверяет, что regeneration не оставляет diff.

Конкретный generator выбирается вместе с Kotlin backend framework и до этого
имеет значение `TBD`.

Operational endpoints отделены от домена:

- public `/healthz` в `openapi.yaml` принадлежит API Gateway;
- `/healthz` и `/readyz` каждого backend-компонента определены в `runtime.yaml`;
- Health Data Service обслуживает activity/weight API под tag `health`, но не
  probes других сервисов.

## Consequences

- Ручные routes/DTO, дублирующие OpenAPI, запрещены.
- Breaking contract changes требуют согласованной миграции server и clients.
- Стабильные `operationId` являются именами generated API и меняются только как
  breaking change.
- Event contracts и NATS consumers не генерируются из OpenAPI; это отдельный
  контрактный контур.
