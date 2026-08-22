# COOKie architecture

Каноническое, версионируемое и пригодное для LLM описание целевой архитектуры.

## Authority

1. Accepted ADR определяют долгоживущие технические решения.
2. YAML в `model/` определяет сервисы, события и синхронные зависимости.
3. Markdown в этом каталоге объясняет семантику и инварианты.
4. [Miro board](https://miro.com/app/board/uXjVGuhJKXc=/) — визуальное зеркало и
   источник исходной декомпозиции, но не канонический текстовый контракт.
5. Код и deploy-конфигурация должны соответствовать пунктам 1–3.

Если Miro и Git расходятся, нельзя молча выбирать одну версию: создаётся ADR или
атомарное изменение модели и доски. Пользовательские решения, зафиксированные в
ADR 0003–0010, имеют приоритет над более старым содержимым доски.

## Reading order for an LLM

1. `overview.md` — границы системы и общая топология.
2. `model/services.yaml` — владельцы данных и назначение сервисов.
3. `model/events.yaml` — event envelope и каталог сообщений.
4. `model/sync-calls.yaml` — разрешённые синхронные зависимости.
5. `http-contracts.md` — OpenAPI ownership и генерация transport-кода.
6. `service-access-control.md` — clients, workload identity и caller policies.
7. `messaging.md` — точная семантика outbox/inbox.
8. `observability.md` — правила наблюдаемости.
9. `service-platform.md` — стандартная оболочка и lifecycle Kotlin-сервиса.
10. `miro-snapshot.md` — происхождение модели и найденные расхождения.
11. `../adr/` — причины принятых решений.

## Non-negotiable invariants

- Backend и Mobile BFF: Kotlin/JVM; edge gateway: Caddy.
- Каждый stateful domain service владеет собственной PostgreSQL БД/схемой.
- Чужие таблицы нельзя читать или изменять напрямую.
- Межсервисные события идут через NATS JetStream.
- Каждый event publisher использует transactional outbox; каждый event consumer
  использует idempotent inbox. Неиспользуемый messaging role заранее не
  создаётся.
- Доставка at-least-once, поэтому consumers идемпотентны.
- Синхронный вызов разрешён только если записан в `sync-calls.yaml`.
- Internal operation доступна только authenticated workload из callee-owned
  caller allowlist; network location и caller-controlled headers не являются
  identity.
- Redis хранит только ephemeral state, перечисленный в `services.yaml`.
- Grafana — единая точка просмотра telemetry; конкретные data sources пока TBD.
- Внешний клиент входит через Caddy; агрегированные mobile screens обслуживает
  Mobile BFF.
- OpenAPI является источником generated server transport interfaces/models и
  HTTP clients; generated code вручную не изменяется.
- Internal client создаётся один раз на callee в отдельном Gradle module; единый
  aggregate clients artifact и per-caller реализации запрещены.
- Kotlin-сервисы создаются через общий template и используют проверяемые build,
  runtime, observability, persistence, messaging и testing conventions.
- Общая service platform не содержит business models или business services.
- Health Data Service владеет доменными данными здоровья, но никогда не владеет
  `/healthz` или `/readyz` других компонентов.
- JDK/framework и Identity publisher profile зафиксированы ADR 0008 и
  `messaging.md`. Неуказанные deployment topology, consumer retry/dead-letter
  policy и telemetry backends нельзя выдумывать: они имеют значение `TBD`.

## Updating the model

Изменение границы сервиса требует одновременно проверить OpenAPI, события,
синхронные вызовы, DBML, deploy и ADR. Новое событие добавляется в
`model/events.yaml` в том же PR, где добавляются producer и consumers.
