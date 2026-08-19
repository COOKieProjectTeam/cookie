# Kotlin service platform

Service platform задаёт одинаковую production-ready оболочку Kotlin/JVM-сервисов
COOKie. Она не заменяет доменную архитектуру и не требует одинакового устройства
бизнес-кода во всех сервисах.

## Goals

- Новый сервис создаётся предсказуемо и не наследует случайные решения другого
  сервиса через copy-paste.
- Cross-cutting behavior реализуется и тестируется один раз.
- Архитектурные и operational invariants проверяются автоматически.
- Сервис остаётся понятным без знания внутреннего устройства всей платформы.
- Общая инфраструктура не превращается в shared business monolith.

## Repository layout

Целевой build layout:

```text
build-logic/                 # Gradle convention plugins
platform/
  starter-web/               # HTTP runtime, errors, request context, probes
  starter-http-client/       # timeouts, identity, tracing and safe telemetry
  starter-security/          # workload and user context enforcement
  starter-observability/     # logs, metrics and tracing conventions
  starter-postgres/          # datasource, migrations and transaction support
  starter-messaging/         # NATS, event envelope, outbox and inbox runtime
  starter-testing/           # reusable test fixtures and mandatory suites
service-template/            # generator/template inputs
clients/
  <callee>/                  # generated shared Kotlin/JVM internal client
services/
  <service-id>/
contracts/
deploy/
infra/
```

Пилотный Gradle bootstrap реализует `starter-web`, `starter-postgres`,
`starter-messaging` и `starter-testing`. Остальные starters добавляются только
при появлении реального потребителя.

## Standard service shape

Минимальная структура domain service:

```text
services/<service-id>/
  build.gradle.kts
  service.yaml
  src/main/kotlin/<package>/
    api/                      # handwritten adapters over generated interfaces
    application/              # commands, queries and use cases
    domain/                   # business model and invariants
    infrastructure/
      postgres/               # repositories and persistence mappings
      messaging/              # publishers and consumers
    configuration/
  src/main/resources/
    application.yaml
    db/migration/
  src/test/
```

Это default, а не запрет на domain-specific packages. Например, сервису может
понадобиться `planning`, `scheduler` или `policy`. Запрещены обратные зависимости
из `domain` в transport или infrastructure и использование generated DTO как
долгоживущей доменной модели.

## Platform modules

### Build logic

Convention plugins централизуют Kotlin/JVM compiler settings, dependency policy,
static analysis, test tasks, generated-source wiring, reproducible builds и
container packaging. Версии инструментов фиксируются в одном месте.

### Web starter

Web starter предоставляет единый error envelope, request/correlation context,
deadline and cancellation hooks, безопасную сериализацию, liveness/readiness и
места расширения для authentication/authorization. Бизнес-handlers остаются в
сервисе и реализуют generated OpenAPI interfaces.

### HTTP client and security starters

HTTP client starter создаёт standard client wiring для per-callee generated
clients: mandatory timeout, trace propagation, workload credential, safe
telemetry и error decoding. Security starter проверяет authenticated workload,
audience, operation permission и user-context mode согласно
`service-access-control.md`. Retry не включается глобально и требует явно
определённой idempotency policy operation.

### Observability starter

Observability starter задаёт структурированные logs, обязательные service and
request attributes, metrics naming и trace propagation. Секреты, credentials,
health data и содержимое пользовательского питания не логируются.

### PostgreSQL starter

PostgreSQL starter задаёт lifecycle datasource, migration validation,
transaction boundaries и интеграционные test fixtures. Он не создаёт общий
database access layer и не разрешает читать таблицы другого сервиса.

### Messaging starter

Messaging starter реализует общий event envelope и инфраструктурную часть
outbox/inbox protocol из `messaging.md`. Domain event payloads и handlers
принадлежат сервисам. Broker acknowledgement выполняется только после commit
consumer transaction.

### Testing starter

Testing starter предоставляет fixtures и стандартные suites, но не скрывает
важные integration boundaries. Сервис обязан иметь собственные domain и
end-to-end tests.

## Service descriptor

Каждый deployable component содержит `service.yaml`. Минимальная семантика:

```yaml
schema_version: 1
id: user
owner: backend
tier: B
runtime: kotlin_jvm
stateful: true
database: user
openapi_tags: [profile]
publishes:
  - user.profile.updated
consumes:
  - account.created
synchronous_dependencies: []
external_dependencies: []
```

JSON Schema descriptor и точные поля создаются вместе с Gradle bootstrap.
Descriptor не дублирует `model/services.yaml`: архитектурная модель определяет
целевую систему, descriptor описывает конкретный deployable component. CI
проверяет их согласованность.

Для deployable service descriptor также содержит callee-owned `access.http` и
`access.messaging` policies. Полная схема и default-deny semantics описаны в
`service-access-control.md`.

## Mandatory verification

Для каждого сервиса CI выполняет применимые проверки:

1. Валидация `service.yaml` и соответствия `model/services.yaml`.
2. Валидация OpenAPI, стабильных `operationId` и generated transport compilation.
3. Проверка production configuration и запуска application context.
4. Проверка migrations на чистой PostgreSQL через disposable test instance.
5. Проверка `/healthz` и `/readyz`, включая поведение при недоступности critical
   dependencies.
6. Проверка event envelope, duplicate delivery и transactional outbox/inbox.
7. Проверка допустимых module и synchronous service dependencies.
8. Unit, integration и contract tests конкретного сервиса.
9. Проверка workload identity, caller permissions и отсутствия internal routes в
   public gateway.

## Rollout

1. Принять отдельный ADR по framework и build stack (ADR 0008).
2. Создать `build-logic` и минимальный набор platform starters.
3. Создать service descriptor schema и generator/template.
4. Реализовать `identity` как пилотный vertical slice: generated HTTP transport,
   PostgreSQL, transactional outbox/inbox, JetStream и runtime probes.
5. Исправить platform API по результатам пилота.
6. Создавать остальные сервисы только через проверенный template.

Пилот не меняет целевые границы из `model/services.yaml`. Он снижает риск
массового размножения непроверенного bootstrap.
