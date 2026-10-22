# COOKie product monorepo

Единый репозиторий исполняемого продукта COOKie.

## Состав

| Каталог | Назначение |
|---|---|
| `apps/api` | временный legacy Go health-check без продуктовой логики |
| `apps/mobile` | общий iOS/Android-клиент на Kotlin Multiplatform |
| `backend/services/identity` | Kotlin/JVM Identity Service v1 (email/password) |
| `backend/services/notification` | Notification: SMTP email, FCM push for Android/iOS, inbox, Redis reminders, devices/settings |
| `backend/tools/service-template` | воспроизводимый Kotlin service scaffold |
| `backend/platform` | тонкие Kotlin runtime/test starters |
| `backend/tools/notification-sink` | отдельный legacy development sink, не используется по умолчанию |
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

Актуальную реализацию можно изучать в
[интерактивном HTML-атласе](docs/architecture/explorer/index.html): сервисы,
все параметры HTTP-операций, subjects, сценарии, таймауты и ссылки на код.
Файл работает без сети. Для пересборки — `make architecture-build`;
[добавление новых сервисов](docs/architecture/explorer/README.md).

Продуктовые исследования и требования находятся в отдельном приватном
репозитории `cookie-product`. Одноразовые проверки внешних API — в
`cookie-labs`.

## Локальный стек

```bash
make dev-up
curl http://localhost:8080/healthz
curl http://localhost:8082/readyz
open http://localhost:8025
```

Compose запускает PostgreSQL с изолированными БД, NATS JetStream, Redis,
Identity, Notification и Mailpit. Identity публикует compact JWE; Notification
атомарно сохраняет inbox/delivery intent, расшифровывает payload в памяти и
доставляет письмо в Mailpit. Порты привязаны к `127.0.0.1`.

Notification предоставляет `/v1/notifications/devices` и
`/v1/notifications/settings`. JWT подписывает Identity; первым PATCH настроек
клиент явно указывает IANA timezone. Redis хранит только восстанавливаемый индекс;
его потеря не теряет расписание. Правила напоминаний, ограничения будущих producer
интеграций и SMTP UNKNOWN описаны в [Notification architecture](docs/architecture/notification.md).

## Production в Yandex Cloud

Production-конфигурация поддерживает Identity и Notification на одной non-HA VM
с PostgreSQL, NATS и Redis. Для каждого сервиса есть независимые tests/image jobs;
образы публикуются через GitHub OIDC и разворачиваются по immutable digest.
Стабильный `CI required` должен быть обязательным check в ruleset `main`.
Постоянного test stand нет: CI использует disposable dependencies.

Gateway публикует только `/v1/auth/*` и `/v1/notifications/*`. Probes и хранилища
остаются закрытыми. Двухсервисный release manifest позволяет обновлять Notification
без замены работающего Identity и восстанавливать прерванные deployments.
Обновление БД не откатывается вместе с образом.

Provisioning и deployment выполняются раздельно по
[production runbook](deploy/production/README.md) и
[Terraform README](infra/terraform/environments/production/README.md).
Начальный бюджет 4 vCPU / 8 GiB требует проверки нагрузкой. Реальный запуск также
требует backups/restore, monitoring, provider credentials и управления ключами.
Регистрация остаётся закрытой до настройки подтверждённого домена и мобильного
обработчика HTTPS-ссылки. Изменения конфигурации сами по себе не разворачивают облако.

## Принципы границ

- Terraform живёт рядом с кодом, который он разворачивает.
- Контракт OpenAPI меняется в одном pull request с реализацией API.
- Технические решения фиксируются ADR рядом с кодом.
- Интервью, персональные данные и продуктовые требования сюда не копируются.
- Эксперимент переносится из `cookie-labs` только после зафиксированного решения.

Nutrition now implements autonomous diary, saved-dish and hydration APIs with
transactional events. See [service instructions](backend/services/nutrition/README.md)
and [ADR 0014](docs/adr/0014-autonomous-nutrition-service.md). Notification PR #20
has merged into main. Nutrition HTTP → outbox → JetStream → the real Notification
consumer is covered by ingestion E2E tests; scheduler/provider delivery is not.
Handwritten Nutrition atlas flows/topology remain a follow-up. The generated
atlas snapshot reflects the current contracts, including exact nutrient strings.
