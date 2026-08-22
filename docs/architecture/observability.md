# Observability

Grafana is the common operator interface for backend health and event delivery.
All Kotlin services, workers, Caddy, PostgreSQL, Redis and NATS JetStream must be
observable there.

Каждый deployable component предоставляет собственные `/healthz` (liveness) и
`/readyz` (readiness). Эти endpoints не проксируются в Health Data Service.

## Required signals

Messaging signals применяются по роли: outbox metrics обязательны для event
publishers, inbox/consumer metrics — только для event consumers.

- HTTP request rate, latency and error rate by route and service.
- Worker throughput, failures and execution latency.
- Outbox pending age/count, publish attempts and failures by service/event type.
- Inbox processed/duplicate/failure counts and consumer lag.
- JetStream consumer lag, redeliveries, advisories and dead-letter volume.
- PostgreSQL pool saturation, transaction latency and errors.
- Redis latency, errors, memory pressure and eviction rate.
- Dependency calls with timeout/error metrics.

## Correlation

HTTP request IDs are persisted into emitted events as `correlation_id`.
`trace_id` is populated only when a tracing bridge installs it in MDC;
`causation_id` is populated by event consumers. Identity v1 has no tracing
bridge or consumer yet, so both fields remain null rather than being fabricated.
Logs must include service, environment and operation, but never tokens,
credentials, detailed health data or meal history.

Identity currently logs publish failures but does not yet export the required
outbox gauges/counters or distributed traces. Those signals and their alerts are
a production rollout requirement, not an implemented pilot capability.

## Alerts

At minimum alert on unavailable service, growing outbox age, sustained consumer
lag, dead-letter messages, database saturation and Redis/NATS unavailability.

## Explicit TBD

Grafana is accepted. Metrics, logs and traces storage/export components are not
yet selected; Prometheus, Loki, Tempo and OpenTelemetry must not be treated as
accepted until a separate ADR chooses them.
