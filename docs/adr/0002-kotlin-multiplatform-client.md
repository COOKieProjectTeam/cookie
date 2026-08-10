# ADR 0002: Kotlin Multiplatform для мобильного клиента

- Status: proposed
- Date: 2026-08-10

## Context

Нужен один клиент для Android и iOS с максимальной долей Kotlin-кода.

## Proposed decision

Использовать Kotlin Multiplatform и Compose Multiplatform для общей логики и UI.
Оставить минимальный iOS wrapper для Xcode, подписи и публикации.

## Open inputs

- Android application ID;
- Apple bundle ID и signing team;
- минимальные версии Android и iOS;
- название приложения в сторах.
