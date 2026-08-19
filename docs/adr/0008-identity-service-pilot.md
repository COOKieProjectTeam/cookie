# ADR 0008: Identity Service как пилот Kotlin service platform

- Status: accepted
- Date: 2026-08-19

## Context

ADR 0003 и ADR 0006 зафиксировали Kotlin/JVM и общую service platform, но
оставили JDK, framework, persistence и OpenAPI generator неопределёнными. Для
первого реального vertical slice нужен воспроизводимый toolchain и минимальный
набор runtime primitives.

## Decision

Identity Service становится первым пилотом платформы. Репозиторий использует:

- JDK 25, Gradle Wrapper 9.6.1, Kotlin 2.3.21 и Spring Boot 4.1.0;
- blocking Spring MVC, Spring JDBC/Hikari и явный SQL без ORM;
- PostgreSQL 18 и Flyway для всех изменений схемы;
- NATS Server 2.14 и `jnats` 2.26 с JetStream;
- OpenAPI Generator 7.24.0: `kotlin-spring` interfaces/transport DTO для JVM и
  Kotlin Multiplatform client из того же public contract;
- generated sources только под `build/generated`; коммитить и редактировать их
  вручную запрещено.

Начальная platform surface состоит из convention build rules и небольших
`starter-web`, `starter-postgres`, `starter-messaging`, `starter-testing`.
Identity остаётся владельцем предметной логики; общий production-код попадает в
starter только после второго потребителя, согласно ADR 0006.

## Consequences

- Go API stub удаляется после появления Kotlin probes и tests.
- Identity deployable проверяет liveness локально, а readiness — PostgreSQL,
  JetStream и доступность ключей.
- Любая смена major toolchain/runtime требует отдельного ADR и зелёной
  regeneration/compile проверки OpenAPI.
