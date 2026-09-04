# ADR 0008: Identity Service как пилот Kotlin service platform

- Status: accepted
- Date: 2026-08-19
- Amended: 2026-09-04

## Context

ADR 0003 и ADR 0006 зафиксировали Kotlin/JVM и общую service platform, но
оставили JDK, framework, persistence и OpenAPI generator неопределёнными. Для
первого реального vertical slice нужен воспроизводимый toolchain и минимальный
набор runtime primitives.

## Decision

Identity Service становится первым пилотом платформы. Репозиторий использует:

- JDK 25, Gradle Wrapper 9.7.1, Kotlin 2.4.10 и Spring Boot 4.1.1; эта
  комбинация остаётся внутри официальной fully-supported Kotlin/Gradle matrix;
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

Identity v1 является event publisher и не является event consumer: он использует
transactional outbox, но не создаёт inbox и consumer runtime заранее.

Identity разделён на отдельные `domain`, `application` и deployable adapter
modules. Domain не зависит от framework; application знает только domain и
ports; Spring/JDBC/NATS/Jackson/Nimbus остаются во внешнем module.

## Consequences

- Go API stub удаляется после появления Kotlin probes и tests.
- Identity HTTP readiness зависит от доступности PostgreSQL и от успешно
  загруженного при startup signing/encryption key material. Если обязательные
  ключи нельзя загрузить, application context не стартует.
- Недоступность NATS JetStream не снимает HTTP pod из readiness: бизнес-state и
  outbox фиксируются атомарно, publisher продолжает retry после восстановления
  брокера. Деградация видна через outbox age/attempt/failure и NATS telemetry и
  должна приводить к alert, а не к удалению HTTP pod из service endpoints.
- Любая смена major toolchain/runtime требует отдельного ADR и зелёной
  regeneration/compile проверки OpenAPI.
