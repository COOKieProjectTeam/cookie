# ADR 0001: границы репозиториев

- Status: accepted; backend language superseded by ADR 0003
- Date: 2026-08-10

## Decision

Исполняемый продукт хранится в одном монорепозитории: backend, Kotlin
Multiplatform client, API-контракты и Terraform. Исследования и требования
хранятся в приватном `cookie-product`, одноразовые эксперименты — в
`cookie-labs`.

## Consequences

Изменение API, клиента и инфраструктуры можно провести атомарно. Персональные
данные интервью не попадают в историю кода. Эксперименты не загрязняют основной
build graph.
