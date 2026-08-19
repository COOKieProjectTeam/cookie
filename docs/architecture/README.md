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
атомарное изменение модели и доски. Пользовательские решения от 2026-08-19,
зафиксированные в ADR 0003/0004, имеют приоритет над более старым содержимым
доски.

## Reading order for an LLM

1. `overview.md` — границы системы и общая топология.
2. `model/services.yaml` — владельцы данных и назначение сервисов.
3. `model/events.yaml` — event envelope и каталог сообщений.
4. `model/sync-calls.yaml` — разрешённые синхронные зависимости.
5. `messaging.md` — точная семантика outbox/inbox.
6. `observability.md` — правила наблюдаемости.
7. `miro-snapshot.md` — происхождение модели и найденные расхождения.
8. `../adr/` — причины принятых решений.

## Non-negotiable invariants

- Backend и Mobile BFF: Kotlin/JVM; edge gateway: Caddy.
- Каждый stateful domain service владеет собственной PostgreSQL БД/схемой.
- Чужие таблицы нельзя читать или изменять напрямую.
- Межсервисные события идут через NATS JetStream.
- Каждый stateful domain service использует transactional outbox и inbox.
- Доставка at-least-once, поэтому consumers идемпотентны.
- Синхронный вызов разрешён только если записан в `sync-calls.yaml`.
- Redis хранит только ephemeral state, перечисленный в `services.yaml`.
- Grafana — единая точка просмотра telemetry; конкретные data sources пока TBD.
- Внешний клиент входит через Caddy; агрегированные mobile screens обслуживает
  Mobile BFF.
- Неуказанные framework, JDK version, retry limits, stream topology и telemetry
  backends нельзя выдумывать: они имеют значение `TBD`.

## Updating the model

Изменение границы сервиса требует одновременно проверить OpenAPI, события,
синхронные вызовы, DBML, deploy и ADR. Новое событие добавляется в
`model/events.yaml` в том же PR, где добавляются producer и consumers.
