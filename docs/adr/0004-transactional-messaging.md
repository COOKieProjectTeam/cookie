# ADR 0004: NATS JetStream, transactional outbox и inbox

- Status: accepted
- Date: 2026-08-19
- Amended: 2026-08-20

## Context

Доменные сервисы обмениваются событиями, но атомарная транзакция между
PostgreSQL и брокером невозможна. NATS JetStream предоставляет durable delivery,
но повторная доставка и сбой между publish acknowledgement и локальным update
остаются нормальными сценариями.

## Decision

Паттерны применяются по фактической роли сервиса:

- сервис, публикующий durable events, имеет локальную таблицу `outbox_events` в
  принадлежащей ему PostgreSQL БД/схеме;
- сервис, потребляющий events, имеет локальную таблицу `inbox_events`;
- producer-only service не создаёт inbox заранее, а consumer-only service не
  создаёт outbox, пока его обработчики сами не начнут публиковать события.

Identity v1 публикует события и ничего не потребляет, поэтому ему нужен outbox,
но не inbox.

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
- Добавление первого consumer в сервис требует добавить inbox migration,
  transactional consumer и duplicate-delivery test в одном изменении.
- Identity хранит published outbox 7 дней и очищает его bounded batches;
  development stream ограничен 7 днями, 1 GiB, 1 000 000 сообщений и 1 MiB на
  сообщение. Production quotas/replicas задаёт deployment layer. Consumer
  retry/dead-letter/inbox retention остаются TBD до первого реального consumer.
