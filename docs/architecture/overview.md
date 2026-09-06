# System overview

COOKie — mobile-first система планирования питания. Клиент обращается к Caddy;
простые команды и ресурсы маршрутизируются в доменные API, а составные экраны —
в Mobile BFF. Stateful-сервисы изолируют данные в PostgreSQL и синхронизируют
локальные модели событиями NATS JetStream.

```mermaid
flowchart LR
    Client["Kotlin Multiplatform client"] --> Gateway["Caddy API Gateway"]
    Gateway --> BFF["Kotlin Mobile BFF"]
    Gateway --> Identity
    Gateway --> User
    Gateway --> FoodCatalog["Food Catalog"]
    Gateway --> Nutrition
    Gateway --> Recipe
    Gateway --> Shopping
    Gateway --> HealthData["Health Data Service"]
    Gateway --> Media
    Gateway --> MealPlanner["Meal Planner"]

    BFF --> User
    BFF --> Nutrition
    BFF --> Recipe
    BFF --> Progress

    Identity <--> Bus["NATS JetStream"]
    User <--> Bus
    FoodCatalog <--> Bus
    Nutrition <--> Bus
    Recipe <--> Bus
    Shopping <--> Bus
    HealthData <--> Bus
    Progress <--> Bus
    Notification <--> Bus
    Media <--> Bus
    MealPlanner <--> Bus

    DomainServices["Every stateful service"] --> PG["Owned PostgreSQL DB/schema"]
    DomainServices -->|when publishing events| Outbox["Transactional outbox"]
    DomainServices -->|when consuming events| Inbox["Idempotent inbox"]

    Gateway --> Redis
    FoodCatalog --> Redis
    Notification --> Redis
    Services["Backend services"] --> Grafana["Grafana observability"]
```

## Runtime roles inside a stateful service

- `API` принимает синхронные HTTP-запросы.
- `Processor Worker` выполняет локальную асинхронную работу.
- `Publisher Worker` отправляет pending outbox records в JetStream и существует
  только у event publisher.
- `Consumer Worker` применяет входящие события через inbox и существует только у
  event consumer. Identity v1 ничего не потребляет и не имеет этой роли.
- Специализированные workers допустимы там, где они явно нужны: scheduler и
  delivery в Notification, generator/processor в Meal Planner.

Это логические роли. ADR 0012 фиксирует только первый production slice: один
контейнер Identity вместе с PostgreSQL и NATS на одной non-HA VM. Он не задаёт
топологию или масштабирование будущих сервисов.

## Initial production slice

Постоянный test stand пока отсутствует: pull request проверяется CI и
disposable Testcontainers, а development stack запускается локально. В Yandex
Cloud разворачивается только Identity:

```mermaid
flowchart LR
    Client["Mobile client"] -->|HTTPS default domain| YAG["Yandex API Gateway"]
    YAG -->|VPC, /v1/auth/* only| Identity
    subgraph VM["one non-HA production VM"]
        Identity --> PG["PostgreSQL"]
        Identity --> NATS["NATS JetStream"]
    end
    CI["GitHub Actions"] -->|OIDC, push SHA image| YCR["Yandex Container Registry"]
    YCR -->|VM identity, pull by digest| VM
```

API Gateway является временным TLS ingress до собственного домена и целевого
Caddy edge. Он не проксирует component probes. Реализация Notification Service
ещё отсутствует, поэтому development Notification sink и Mailpit в production
не попадают.

## Health terminology

`Health Data Service` — доменный сервис веса и активности. Он не является
центральным сервисом проверки работоспособности. Caddy, Mobile BFF и каждый
backend runtime самостоятельно предоставляют `/healthz` и `/readyz` по общему
операционному контракту `contracts/openapi/runtime.yaml`. Эти component-local
probes не входят в product public OpenAPI. Target production deployment обязан
разрешать их только orchestrator и операторам через deployment network. Caddy
проверяется собственной probe на отдельной operational boundary и не должен
проксировать probe другого сервиса.
