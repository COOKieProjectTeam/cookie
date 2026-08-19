# Observability

Grafana is the common operator interface for backend health and event delivery.
All Kotlin services, workers, Caddy, PostgreSQL, Redis and NATS JetStream must be
observable there.

## Required signals

- HTTP request rate, latency and error rate by route and service.
- Worker throughput, failures and execution latency.
- Outbox pending age/count, publish attempts and failures by service/event type.
- Inbox processed/duplicate/failure counts and consumer lag.
- JetStream consumer lag, redeliveries, advisories and dead-letter volume.
- PostgreSQL pool saturation, transaction latency and errors.
- Redis latency, errors, memory pressure and eviction rate.
- Dependency calls with timeout/error metrics.

## Correlation

HTTP and event processing propagate `trace_id`, `correlation_id` and
`causation_id`. Logs are structured and include service, environment and
operation, but never tokens, credentials, detailed health data or meal history.

## Alerts

At minimum alert on unavailable service, growing outbox age, sustained consumer
lag, dead-letter messages, database saturation and Redis/NATS unavailability.

## Explicit TBD

Grafana is accepted. Metrics, logs and traces storage/export components are not
yet selected; Prometheus, Loki, Tempo and OpenTelemetry must not be treated as
accepted until a separate ADR chooses them.
