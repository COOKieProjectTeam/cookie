# ADR 0004: NATS JetStream, transactional outbox и inbox

- Status: accepted
- Date: 2026-08-19

## Context

Доменные сервисы обмениваются событиями, но атомарная транзакция между
PostgreSQL и брокером невозможна. NATS JetStream предоставляет durable delivery,
но повторная доставка и сбой между publish acknowledgement и локальным update
остаются нормальными сценариями.

## Decision

Каждый stateful domain service имеет локальные таблицы `outbox_events` и
`inbox_events` в принадлежащей ему PostgreSQL БД/схеме.

- Бизнес-изменение и запись outbox выполняются в одной DB-транзакции.
- Publisher worker публикует outbox в NATS JetStream и использует `event_id` как
  deduplication/message id.
- Consumer worker начинает DB-транзакцию, регистрирует `event_id` в inbox,
  применяет изменение и при необходимости создаёт новый outbox event.
- NATS acknowledgement отправляется только после commit.
- Доставка считается at-least-once; бизнес-обработчики обязаны быть
  идемпотентными.

Полный протокол и envelope находятся в `docs/architecture/messaging.md` и
`docs/architecture/model/events.yaml`.

## Consequences

- Прямое dual-write `PostgreSQL + NATS` запрещено.
- Redis не используется как outbox, inbox или источник доменных данных.
- Повторные события не должны повторно применять бизнес-эффект.
- Политики retention, retry, dead-letter и cleanup являются обязательной частью
  runtime-конфигурации, но их числовые значения пока TBD.
