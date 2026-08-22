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
build-logic/                         # Gradle convention plugins
backend/services/<service-id>/       # independently deployable domain service
backend/services/<service-id>/domain/ # framework-free aggregates and value objects
backend/services/<service-id>/application/ # use cases and ports
backend/platform/starter-web/        # HTTP runtime, errors, request context, probes
backend/platform/starter-http-client/ # timeouts, identity, tracing and safe telemetry
backend/platform/starter-security/   # workload and user context enforcement
backend/platform/starter-observability/ # logs, metrics and tracing conventions
backend/platform/starter-postgres/   # datasource, migrations and transaction support
backend/platform/starter-messaging/  # NATS primitives and the common event envelope
backend/platform/starter-testing/    # reusable test fixtures and mandatory suites
backend/tools/                       # backend development and operations applications
backend/clients/<callee>/            # created only with the first real consumer
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
backend/services/<service-id>/
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

Сейчас Messaging starter реализует общий event envelope, subject mapping и
NATS client primitives. Первая outbox-реализация остаётся локальным adapter
Identity; переносить её в platform до второго реального publisher нельзя.
Аналогично inbox runtime появится вместе с первым реальным consumer, а станет
общим только после проверки повторным потребителем. Domain event payloads и
handlers всегда принадлежат сервисам. Broker acknowledgement consumer выполняет
только после commit consumer transaction.

### Testing starter

Testing starter предоставляет fixtures и стандартные suites, но не скрывает
важные integration boundaries. Сервис обязан иметь собственные domain и
end-to-end tests.

## Service descriptor

Каждый deployable component содержит `service.yaml`. Минимальная семантика:

```yaml
schema_version: 1
id: user
runtime: kotlin_jvm
stateful: true
database: user
public_openapi:
  source: contracts/openapi/public/user.yaml
runtime_openapi: contracts/openapi/runtime.yaml
messaging:
  publishes:
    - type: user.profile.updated
      version: 1
  consumes:
    - type: account.activated
      version: 1
synchronous_dependencies: []
infrastructure_dependencies: [postgresql, nats_jetstream]
probes:
  liveness: /healthz
  readiness: /readyz
```

Gradle task `validateServiceDescriptors` уже проверяет разрешённые поля и
согласованность descriptor с service/event models, active OpenAPI, runtime
contract, dependencies и event ownership. Отдельная JSON Schema и generator
service template ещё не реализованы; архитектурная модель в
`model/services.yaml` остаётся источником целевых service boundaries.

Для deployable service descriptor также содержит callee-owned `access.http`
policies. Messaging allowlist выводится непосредственно из проверенных
`messaging.publishes/consumes`, без второй копии тех же subjects. Полная схема и
default-deny semantics описаны в `service-access-control.md`.

## Target verification

CI выполняет реализованные проверки для каждого сервиса; пункты без runtime
implementation остаются rollout requirements:

1. Валидация `service.yaml` и соответствия `model/services.yaml`.
2. Валидация OpenAPI, стабильных `operationId` и generated transport compilation.
3. Проверка production configuration и запуска application context.
4. Проверка migrations на чистой PostgreSQL через disposable test instance.
5. Проверка `/healthz` и `/readyz`, включая поведение при недоступности critical
   dependencies.
6. Проверка event envelope и transactional outbox у publishers; duplicate
   delivery и idempotent inbox у consumers.
7. Проверка допустимых module и synchronous service dependencies.
8. Unit, integration и contract tests конкретного сервиса.
9. Проверка workload identity, caller permissions и отсутствия internal routes в
   public gateway.

## Rollout

1. Принять отдельный ADR по framework и build stack (ADR 0008).
2. Создать `build-logic` и минимальный набор platform starters.
3. Дополнить semantic descriptor validator JSON Schema и generator/template
   (ещё не реализованы).
4. Реализовать `identity` как пилотный vertical slice: generated HTTP transport,
   PostgreSQL, transactional outbox, JetStream и runtime probes.
5. Исправить platform API по результатам пилота.
6. Создавать остальные сервисы только через проверенный template.

Пилот не меняет целевые границы из `model/services.yaml`. Он снижает риск
массового размножения непроверенного bootstrap.
