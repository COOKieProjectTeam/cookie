# ADR 0007: синхронные клиенты и service-to-service access control

- Status: accepted
- Date: 2026-08-19

## Context

Несколько сервисов могут синхронно вызывать один и тот же upstream. Независимая
реализация HTTP-клиента в каждом caller приводит к расхождению transport models,
authentication, timeouts, telemetry и обработки ошибок. При этом наличие общего
клиента не должно само по себе разрешать новую синхронную зависимость.

Сервис также должен отличать внешний запрос от запроса другого workload и
проверять разрешение на конкретную operation. IP-адрес, DNS-имя, network location
и переданный клиентом заголовок с именем сервиса не доказывают identity.

## Decision

### Per-callee clients

Для каждого синхронно вызываемого сервиса создаётся отдельный Kotlin/JVM client
module в `clients/<callee>`. Client и server transport генерируются из одного
internal OpenAPI contract, которым владеет callee.

Caller зависит только от нужного client module. Единый `cookie-all-clients`
запрещён. Generated transport models не становятся общими domain models;
caller преобразует их в собственные модели через локальный adapter.

### Two levels of permission

`docs/architecture/model/sync-calls.yaml` остаётся coarse-grained архитектурным
allowlist: `caller -> callee`, purpose и требования к деградации.

`services/<callee>/service.yaml` содержит callee-owned operation policy:

- internal OpenAPI operations;
- semantic permission;
- подтверждённых callers;
- требование или запрет user context;
- exposure и authentication mode.

Локальный grant допустим только при наличии соответствующего ребра в
`sync-calls.yaml`. Наличие Gradle dependency на client module не является
разрешением на runtime-вызов.

### Authenticated workload identity

Каждый internal request обязан содержать криптографически подтверждаемую
workload identity. Callee проверяет signature или certificate chain, issuer,
validity period, intended audience и stable workload subject до проверки
operation policy.

Если первая production topology работает в Kubernetes, начальная реализация
использует отдельный ServiceAccount для каждого deployable service и bound
projected short-lived ServiceAccount token с explicit audience callee. Static
long-lived service tokens и общий shared secret запрещены.

SPIFFE-compatible mTLS identity может заменить или усилить bearer tokens без
изменения semantic permissions и caller policies. Конкретная реализация
workload issuer, certificate authority и token transport уточняется вместе с
production deployment topology.

User identity не используется как service identity. Операция явно объявляет
один из режимов user context: `required`, `optional` или `forbidden`. Caller name
и user identifier нельзя принимать из неподписанных доверительных заголовков.

### Defense in depth

- Internal routes не публикуются через public Caddy ingress.
- Default-deny network policy ограничивает ingress и egress по разрешённому
  service graph, но не заменяет application authorization.
- Platform security runtime применяет operation policy и по умолчанию запрещает
  неизвестные operations и callers.
- NATS credentials получают независимые publish/subscribe allowlists по subjects
  из event model и service descriptor.
- Authentication and authorization denials попадают в security telemetry без
  записи credential или sensitive payload.

## Consequences

- Изменение internal contract атомарно обновляет server transport, per-callee
  client и затронутых consumers.
- Добавление нового caller требует изменения `sync-calls.yaml`, callee policy,
  caller dependency и security/degradation tests в одном pull request.
- CI сопоставляет service graph, internal OpenAPI, client dependencies и callee
  grants; расхождения блокируют merge.
- Common client содержит transport behavior, но не orchestration, caller-specific
  fallback или business rules.
- Retry не включается глобально для всех requests. Он разрешён только для
  operations с явно определённой idempotency и ограниченной retry policy.
- Компрометация workload credential остаётся частью threat model; short lifetime,
  audience restriction, rotation и least privilege ограничивают blast radius.
