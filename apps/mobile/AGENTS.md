# Agent instructions — mobile

## Scope

These rules apply to the Kotlin Multiplatform Android/iOS client.

## Architecture

- Put platform-independent logic in `commonMain`.
- Use `expect`/`actual` only for genuine platform capabilities.
- Keep Android and iOS wrappers thin; shared UI is intended to use Compose
  Multiplatform after bootstrap.
- Do not expose platform framework types from shared domain APIs.
- Model network payloads separately from domain models.
- Handle offline, cancellation and lifecycle changes explicitly.

## Product safety

- Avoid streaks, punishment for pauses and aggressive weight-loss language.
- Prefer ranges and neutral feedback over hard red/green calorie judgments.
- Accessibility labels and scalable text are required for interactive UI.
- Never log auth tokens, health data or detailed meal history.

## Bootstrap constraint

Do not invent production application IDs or signing values. Before completing
Gradle/Xcode bootstrap, record Android application ID, Apple bundle ID, signing
team and minimum OS versions in ADR 0002.
