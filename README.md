# COOKie product monorepo

Единый репозиторий исполняемого продукта COOKie.

## Состав

| Каталог | Назначение |
|---|---|
| `apps/api` | временный legacy Go health-check без продуктовой логики |
| `apps/mobile` | общий iOS/Android-клиент на Kotlin Multiplatform |
| `backend/services/identity` | Kotlin/JVM Identity Service v1 (email/password) |
| `backend/platform` | тонкие Kotlin runtime/test starters |
| `backend/tools/notification-sink` | локальная расшифровка email-событий и доставка в Mailpit |
| `contracts/openapi/public` | активные публичные контракты по одному на сервис |
| `infra/terraform` | инфраструктура и окружения |
| `deploy/docker` | локальная development-сборка контейнеров |
| `deploy/production` | production Compose, bootstrap и deployment runbook |
| `docs/adr` | технические решения |
| `docs/architecture` | каноническое LLM-friendly описание архитектуры |

## Целевая архитектура

Backend переводится на Kotlin/JVM и разбивается по доменным сервисам. Каждый
stateful-сервис владеет своей PostgreSQL-схемой/БД. Сервис, публикующий durable
events, использует transactional outbox; сервис, потребляющий события, —
idempotent inbox. Producer-only Identity v1 поэтому содержит outbox, но не
inbox. Redis используется только для явно описанных ephemeral-задач; Grafana —
единая точка наблюдаемости.

HTTP transport следует contract-first подходу: server interfaces и клиенты
генерируются из service-owned OpenAPI. Общий mobile/gateway bundle автоматически
собирается только из активных сервисных контрактов; сгенерированный код не
редактируется вручную.

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
make dev-up
curl http://localhost:8080/healthz
open http://localhost:8025
```

Compose запускает PostgreSQL 18, NATS JetStream, Identity, локальный
Notification sink и Mailpit. Identity публикует только compact JWE; sink
расшифровывает его ephemeral ключом из локального volume и отправляет письмо в
Mailpit. Все опубликованные Compose-порты привязаны к `127.0.0.1` и доступны
только с development host. Для запуска без контейнеров: `make identity-run`.
`make dev-build-identity` и `make dev-build-notification-sink` собирают сервисы
независимо; старые `make compose-up`/`compose-down` сохранены как aliases.

## Production в Yandex Cloud

Постоянного test stand пока нет. Pull request проходит только проверки затронутых
сервисов и их реальных shared-зависимостей с disposable dependencies. Для
блокировки merge ruleset ветки `main` должен требовать один стабильный check
`CI required`: он падает, если change detection или любая выбранная проверка
завершилась неуспешно. Настройка ruleset и точная матрица путей описаны в
[`.github/README.md`](.github/README.md).

`push` в `main` после зелёного CI публикует только изменившийся deployable
Identity image в Yandex Container Registry. Каждый будущий сервис добавляется
отдельным test и publish job; `latest` при deployment не используется.

CI входит в Yandex Cloud через GitHub OIDC, без service-account key. Для него
нужны repository variables `YC_REGISTRY_ID` и `YC_CI_SERVICE_ACCOUNT_ID`.
Production VM получает отдельное pull-only identity и скачивает образ по digest;
исходники и build toolchain на сервер не копируются.

Начальный контур — одна небольшая non-HA VM с Identity, PostgreSQL и NATS.
Пока собственного домена нет, публичный `/v1/auth/*` доступен через default HTTPS
domain Yandex API Gateway; probes, PostgreSQL, NATS и SSH остаются закрытыми.
Provisioning и deployment выполняются раздельно по инструкциям в
[`infra/terraform/environments/production`](infra/terraform/environments/production/README.md)
и [`deploy/production`](deploy/production/README.md). Решение и его ограничения
зафиксированы в [ADR 0012](docs/adr/0012-yandex-single-environment-deployment.md).

## Принципы границ

- Terraform живёт рядом с кодом, который он разворачивает.
- Контракт OpenAPI меняется в одном pull request с реализацией API.
- Технические решения фиксируются ADR рядом с кодом.
- Интервью, персональные данные и продуктовые требования сюда не копируются.
- Эксперимент переносится из `cookie-labs` только после зафиксированного решения.
