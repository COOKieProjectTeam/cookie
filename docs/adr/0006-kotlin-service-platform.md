# ADR 0006: единая платформа Kotlin-сервисов

- Status: accepted
- Date: 2026-08-19
- Amended: 2026-08-20

## Context

Целевая архитектура COOKie состоит из нескольких Kotlin/JVM-сервисов. Без
единого способа создавать и сопровождать сервисы со временем расходятся build,
конфигурация, обработка ошибок, observability, health checks, тесты и правила
работы с PostgreSQL и NATS.

Одинаковые названия пакетов сами по себе не обеспечивают единообразие. Оно
должно формироваться общей service platform: шаблоном проекта, Gradle convention
plugins, небольшими runtime starters, декларативным описанием сервиса и
обязательными CI-проверками.

## Decision

COOKie создаёт внутреннюю Kotlin service platform — стандартный путь от
контракта и нового сервиса до проверяемого deployable artifact.

Платформа включает:

- `build-logic` с Gradle convention plugins и централизованными build rules;
- тонкие platform starters для HTTP runtime, observability, PostgreSQL,
  messaging и тестов;
- генератор или шаблон нового сервиса;
- единый service descriptor с runtime, ресурсами, контрактами и зависимостями;
- generated OpenAPI transport согласно ADR 0005;
- стандартные liveness/readiness endpoints каждого deployable component;
- стандартный набор contract, configuration, integration и architecture tests;
- проверяемую реализацию outbox для event publishers и inbox для event consumers
  согласно ADR 0004.

Единообразной является внешняя оболочка сервиса и его operational contract.
Внутренняя структура отражает предметную область, но сохраняет направленные
границы:

```text
transport adapter -> application use cases -> domain
                                      ^
                                      |
                     infrastructure adapters
```

Generated transport models не являются domain models. HTTP, PostgreSQL и NATS
не должны определять доменную модель.

Новый общий production-код переносится в platform module только после появления
минимум двух реальных потребителей. Общие business models и business services в
platform запрещены.

JDK, framework, persistence stack и generator пилота зафиксированы ADR 0008.
Telemetry backends и любые отличающиеся platform-wide choices выбираются
отдельными ADR и до этого сохраняют значение `TBD`.

## Consequences

- Новый сервис создаётся из проверяемого шаблона, а не копированием существующего
  каталога.
- Исправление cross-cutting поведения выполняется в platform layer и доходит до
  сервисов обновлением версии или build convention.
- Semantic validator уже делает service descriptor машинно-проверяемым
  архитектурным входом CI и сопоставляет его с models/contracts. JSON Schema и
  deploy tooling остаются следующим этапом.
- Domain-specific packages могут различаться между сервисами; обязательны
  зависимости между слоями, а не универсальное дерево папок ради единообразия.
- Platform starters должны оставаться небольшими и composable. Подключение
  одного starter не должно неявно приносить весь инфраструктурный стек.
- Identity держит первую outbox-реализацию в собственном adapter module; общий
  messaging runtime выделяется только после второго реального publisher.
  Inbox runtime не создаётся до появления первого event consumer.
- Первым пилотом становится один vertical slice. Остальные сервисы создаются
  после проверки шаблона на реальном HTTP, PostgreSQL, NATS и observability flow.
