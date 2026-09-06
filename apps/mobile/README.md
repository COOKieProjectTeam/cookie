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
Перед подключением auth UI нужен coordinator и platform adapter защищённого
хранилища. Их точный lifecycle, правила retry/crash recovery и граница между
текущим raw token в Mailpit и будущим universal/app link описаны в
[mobile-auth architecture](../../docs/architecture/mobile-auth.md).

Реализация через Android Keystore и iOS Keychain остаётся заблокирована тем же
незавершённым platform bootstrap. Хранить registration proof или refresh token
в preferences, логах, URL либо синхронизируемом backup запрещено.
