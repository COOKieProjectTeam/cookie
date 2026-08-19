# Miro extraction snapshot

- Board: [COOKie architecture](https://miro.com/app/board/uXjVGuhJKXc=/)
- Extracted: 2026-08-19
- Purpose: provenance, not a second source of truth

## Extracted structure

The board contains a common architecture diagram, per-service runtime diagrams,
per-service database diagrams and four architecture tables. The structured
extraction found:

- 13 listed components: Caddy, Mobile BFF and 11 domain services;
- 18 catalogued domain events;
- 16 catalogued synchronous/external calls;
- 3 explicit Redis use cases;
- repeated PostgreSQL, Publisher Worker, Consumer Worker, Processor Worker and
  NATS JetStream patterns around stateful services;
- separate database models for Identity, User, Food Catalog, Nutrition, Recipe,
  Shopping, Health, Progress, Notification, Media and Meal Planner.

Relevant board items:

- [Common architecture](https://miro.com/app/board/uXjVGuhJKXc=/?moveToWidget=3458764681060769350)
- [Event catalog](https://miro.com/app/board/uXjVGuhJKXc=/?moveToWidget=3458764681060769453)
- [Synchronous calls](https://miro.com/app/board/uXjVGuhJKXc=/?moveToWidget=3458764681060769454)
- [Service catalog](https://miro.com/app/board/uXjVGuhJKXc=/?moveToWidget=3458764681060769481)
- [Redis usage](https://miro.com/app/board/uXjVGuhJKXc=/?moveToWidget=3458764681060769515)

## Decisions applied after the board

These user decisions supersede missing or older board details:

- backend and Mobile BFF use Kotlin/JVM;
- every stateful domain service uses transactional outbox and idempotent inbox;
- PostgreSQL, NATS JetStream, Redis and Grafana are accepted infrastructure;
- Git architecture files are canonical and Miro becomes the visual mirror.

## Known discrepancies and unresolved items

- Recipe's service diagram mentions `recipe.feedback.changed` and
  `recipe.version.published`, but neither appears in the 18-row event catalog.
  They are therefore not accepted events in `model/events.yaml` yet.
- Grafana was not present in the extracted board objects; it was added from the
  explicit 2026-08-19 decision.
- The board mixes labels `NATS` and `NATS JetStream`; the durable event transport
  is normalized to NATS JetStream.
- Backend framework, JDK version, service process/container layout, JetStream
  subjects/streams, retry limits, dead-letter policy and Grafana data sources
  remain TBD.
- The Miro-generated board overview could not be retrieved because the Miro AI
  credit quota was exhausted. Tables and raw board objects were still read
  directly, so this model does not depend on that overview.
