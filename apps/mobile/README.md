# Mobile client

Целевой клиент — Kotlin Multiplatform для Android и iOS. Общая бизнес-логика,
модели, сеть и UI должны находиться в Kotlin; iOS сохраняет минимальный Xcode
wrapper, необходимый для подписи и запуска приложения.

Текущий каталог задаёт границы source sets:

```text
shared/src/
├── commonMain/   общий Kotlin-код
├── androidMain/  Android-реализации expect/actual
└── iosMain/      iOS-реализации expect/actual
```

Gradle/Xcode bootstrap намеренно не зафиксирован до выбора application ID,
Apple bundle ID и signing team. После их определения проект следует создать
официальным Kotlin Multiplatform wizard и сохранить эти границы каталогов.

Public HTTP transport client генерируется из активных OpenAPI-контрактов задачей
`compileKmpPublicClient`. Это низкоуровневый client, не готовый auth workflow.
Перед подключением registration UI нужен platform adapter защищённого хранилища:
он атомарно сохраняет пару `registrationAttemptId`/`registrationProof` до первого
запроса, выбирает proof по attempt id из `v1e` deep-link и удаляет пару только
после подтверждённого (при необходимости повторённого) `204`. Реализация через
Android Keystore и iOS Keychain остаётся заблокирована тем же незавершённым
platform bootstrap; хранить proof в preferences, логах или URL запрещено.
