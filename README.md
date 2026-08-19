# COOKie product monorepo

Единый репозиторий исполняемого продукта COOKie.

## Состав

| Каталог | Назначение |
|---|---|
| `apps/api` | временный legacy Go health-check без продуктовой логики |
| `apps/mobile` | общий iOS/Android-клиент на Kotlin Multiplatform |
| `services/identity` | Kotlin/JVM Identity Service v1 (email/password) |
| `platform` | тонкие Kotlin runtime/test starters |
| `tools/notification-sink` | локальная расшифровка email-событий и доставка в Mailpit |
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

HTTP transport следует contract-first подходу: server interfaces и клиенты
генерируются из OpenAPI, а сгенерированный код не редактируется вручную.

Начинать чтение архитектуры следует с
[`docs/architecture/README.md`](docs/architecture/README.md). Первым production
vertical slice является Identity Service из ADR 0008 и ADR 0009. Legacy
`apps/api` сохраняется до отдельного решения об удалении.

Визуальная схема и исходная декомпозиция сервисов находятся на
[архитектурной доске COOKie в Miro](https://miro.com/app/board/uXjVGuhJKXc=/).

Продуктовые исследования и требования находятся в отдельном приватном
репозитории `cookie-product`. Одноразовые проверки внешних API — в
`cookie-labs`.

## Локальный Identity stack

```bash
make compose-up
curl http://localhost:8080/healthz
open http://localhost:8025
```

Compose запускает PostgreSQL 18, NATS JetStream, Identity, локальный
Notification sink и Mailpit. Identity публикует только compact JWE; sink
расшифровывает его ephemeral ключом из локального volume и отправляет письмо в
Mailpit. Для запуска без контейнеров: `make identity-run`.

## Принципы границ

- Terraform живёт рядом с кодом, который он разворачивает.
- Контракт OpenAPI меняется в одном pull request с реализацией API.
- Технические решения фиксируются ADR рядом с кодом.
- Интервью, персональные данные и продуктовые требования сюда не копируются.
- Эксперимент переносится из `cookie-labs` только после зафиксированного решения.
