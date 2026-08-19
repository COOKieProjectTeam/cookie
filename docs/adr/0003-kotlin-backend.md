# ADR 0003: Kotlin/JVM для backend

- Status: accepted
- Date: 2026-08-19

## Context

Первоначальный каркас API был создан на Go, но целевая архитектура включает
несколько доменных сервисов и должна использовать общий язык и экосистему с
Kotlin Multiplatform-клиентом там, где это оправдано.

## Decision

Все backend-сервисы COOKie и Mobile BFF реализуются на Kotlin/JVM. Caddy остаётся
edge gateway и не считается Kotlin-сервисом. Конкретный серверный framework,
JDK, build layout и библиотечный стек выбираются отдельным решением; до этого их
нельзя угадывать по умолчанию.

Текущий Go health-check — временный migration stub. Новая бизнес-логика в него
не добавляется.

## Consequences

- Go bootstrap удаляется после появления Kotlin health/readiness endpoints.
- CI получает Gradle-задачи для backend после фиксации bootstrap.
- OpenAPI и event contracts не зависят от выбранного Kotlin framework.
- Общий код между mobile и backend допускается только при реальном общем
  семантическом контракте, а не ради механического переиспользования DTO.
