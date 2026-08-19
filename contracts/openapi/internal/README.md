# Internal service contracts

Каждый файл `contracts/openapi/internal/<service-id>.yaml` принадлежит вызываемому
сервису и является источником generated Kotlin/JVM server transport и отдельного
`clients/<service-id>` module.

Internal operations должны:

- иметь стабильный уникальный `operationId`;
- объявлять workload authentication;
- иметь стабильную `x-cookie-permission`;
- задавать `x-cookie-user-context` как `required`, `optional` или `forbidden`;
- соответствовать caller grants в service descriptor;
- использоваться только зависимостями из `docs/architecture/model/sync-calls.yaml`.

Generated sources не хранятся в этом каталоге и не коммитятся. До реализации
первого синхронного vertical slice здесь нет placeholder OpenAPI specification:
контракт добавляется вместе с server handler, client consumer и contract tests.
