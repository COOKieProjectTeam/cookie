# ADR 0010: Per-service public OpenAPI и generated active bundle

- Status: accepted
- Date: 2026-08-20

## Context

Один вручную поддерживаемый public OpenAPI содержал операции всех целевых
сервисов, включая ещё не реализованные. Server generation можно было ограничить
тегом, но общий KMP client получал методы для несуществующих backend handlers.
Ручной список generated models в каждом сервисе дополнительно создавал риск
расхождения контракта и сборки.

## Decision

Каждый реализованный deployable service владеет одним public source contract:

```text
contracts/openapi/public/<service-id>.yaml
```

Контракт добавляется в active directory только вместе с generated server
interface, компилирующим handwritten handler и проверками сервиса. Server code
генерируется напрямую из service-owned файла без ручного перечня transport
models.

Gradle task `bundlePublicOpenApi` автоматически объединяет active service
contracts в:

```text
build/generated/openapi/bundled/public.yaml
```

Bundle является disposable build output и единственным источником общего KMP
client и gateway-facing validation. Bundler запрещает duplicate routes и
конфликтующие tags, components, servers или top-level security defaults.

Существующий целевой API сохранён в `contracts/openapi/planned.yaml` как roadmap.
Он не участвует ни в server, ни в client generation. Операция становится active
только после переноса в контракт реализующего её сервиса.

Internal contracts остаются callee-owned файлами в
`contracts/openapi/internal/<service-id>.yaml` и генерируют по одному JVM client
module на callee при появлении реального consumer.

## Consequences

- Generated mobile client содержит только реализованные public operations.
- Изменения одного сервиса не требуют редактировать общий handwritten документ.
- Глобальные конфликты paths, tags и components обнаруживаются при bundling.
- Общие schemas допустимы только при идентичном определении; разные определения
  одного component name приводят к ошибке сборки.
- Roadmap не является исполняемым контрактом и не обещает доступность API.
