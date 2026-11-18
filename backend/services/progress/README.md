# Progress Service

Progress owns diary, weight and achievement projections in its PostgreSQL
database. See [ADR 0017](../../../docs/adr/0017-progress-day-boundaries.md)
for commit evidence, ownership and calendar semantics.

- Inbox and projections commit together under an account lock. A source's
  sequence advances across complete commit groups only.
- A delayed event may correct a displayed fact after closing. A confirmed
  result remains immutable and counts once per account and calendar date.
- Closing waits for User and Nutrition evidence. Health and Notification
  are independent of achievement certification.
- Account deletion uses the same locks as writes, workers and authenticated
  reads. Data erasure and the deletion tombstone commit together.

Domain tests run with `./gradlew :backend:services:progress:domain:test`.
Run the PostgreSQL tests with `./gradlew :backend:services:progress:integrationTest`.
