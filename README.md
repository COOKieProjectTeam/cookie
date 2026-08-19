# COOKie product monorepo

Единый репозиторий исполняемого продукта COOKie.

## Состав

| Каталог | Назначение |
|---|---|
| `apps/api` | Временный Go health-check; целевой backend — Kotlin/JVM-сервисы |
| `apps/mobile` | общий iOS/Android-клиент на Kotlin Multiplatform |
| `contracts/openapi` | публичный контракт API |
| `infra/terraform` | инфраструктура и окружения |
| `deploy/docker` | локальная контейнерная сборка |
| `docs/adr` | технические решения |
| `docs/architecture` | каноническое LLM-friendly описание архитектуры |

## Целевая архитектура

Backend переводится на Kotlin/JVM и разбивается по доменным сервисам. Каждый
stateful-сервис владеет своей PostgreSQL-схемой/БД и использует transactional
outbox и idempotent inbox для обмена событиями через NATS JetStream. Redis
используется только для явно описанных ephemeral-задач; Grafana — единая точка
наблюдаемости.

Начинать чтение архитектуры следует с
[`docs/architecture/README.md`](docs/architecture/README.md). Текущий Go-код —
bootstrap, который ещё не мигрирован, а не образец для новой реализации.

Визуальная схема и исходная декомпозиция сервисов находятся на
[архитектурной доске COOKie в Miro](https://miro.com/app/board/uXjVGuhJKXc=/).

Продуктовые исследования и требования находятся в отдельном приватном
репозитории `cookie-product`. Одноразовые проверки внешних API — в
`cookie-labs`.

## Текущий bootstrap API

```bash
make test
make api-run
curl http://localhost:8080/healthz
```

Порт можно изменить переменной `PORT`.

## Принципы границ

- Terraform живёт рядом с кодом, который он разворачивает.
- Контракт OpenAPI меняется в одном pull request с реализацией API.
- Технические решения фиксируются ADR рядом с кодом.
- Интервью, персональные данные и продуктовые требования сюда не копируются.
- Эксперимент переносится из `cookie-labs` только после зафиксированного решения.
