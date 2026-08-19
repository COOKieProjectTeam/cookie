# Service-to-service access control

Этот документ определяет, как COOKie разрешает синхронные вызовы, подтверждает
identity caller и применяет authorization policy. Он конкретизирует ADR 0007.

## Core rule

Доступ разрешён только при одновременном выполнении всех условий:

```text
allowed architecture edge
AND authenticated workload identity
AND intended callee audience
AND allowed operation permission
AND valid user context when required
= allowed request
```

Callee владеет internal contract и решает, кто может вызвать каждую operation.
Caller не выдаёт разрешение сам себе подключением client dependency.

## Sources of truth

| Source | Question answered |
|---|---|
| `model/sync-calls.yaml` | Почему `caller -> callee` разрешён архитектурно? |
| `contracts/openapi/internal/<callee>.yaml` | Какие internal operations и transport models существуют? |
| `services/<callee>/service.yaml` | Кто может вызвать operation и какой context обязателен? |
| deployment identity and network policy | Как runtime подтверждает и ограничивает caller? |

Эти источники имеют разную гранулярность. CI проверяет их согласованность.

## Callee-owned policy

Целевая секция service descriptor:

```yaml
schema_version: 1
id: recipe

access:
  http:
    - id: recipe-snapshot-read
      contract: internal
      operations:
        - getRecipeSnapshot
      permission: recipe.snapshot.read
      exposure: internal
      authentication:
        workload_identity: required
        user_context: required
      callers:
        - service: nutrition
        - service: shopping

    - id: recipe-candidates-read
      contract: internal
      operations:
        - findRecipeCandidates
      permission: recipe.candidates.read
      exposure: internal
      authentication:
        workload_identity: required
        user_context: forbidden
      callers:
        - service: meal_planner
```

Policy является default-deny:

- operation без policy недоступна;
- caller вне `callers` получает отказ;
- неизвестная permission считается ошибкой конфигурации;
- wildcard callers запрещены;
- пустой caller allowlist означает deny all, а не allow all.

`exposure` различает как минимум:

- `public_gateway` — только public Caddy route с end-user authentication;
- `internal` — authenticated workload из caller allowlist;
- `operator` — отдельная privileged operator identity;
- `external_callback` — явно определённая проверка upstream signature или mTLS.

## Internal OpenAPI

Internal contracts располагаются отдельно от public API и принадлежат callee:

```yaml
components:
  securitySchemes:
    workloadIdentity:
      type: http
      scheme: bearer
      bearerFormat: JWT

paths:
  /internal/v1/recipes/{recipeId}/snapshot:
    get:
      operationId: getRecipeSnapshot
      security:
        - workloadIdentity: []
      x-cookie-permission: recipe.snapshot.read
      x-cookie-user-context: required
```

`security` определяет authentication scheme. Stable `x-cookie-permission`
связывает operation с service policy независимо от выбранного identity provider.
Если будет принят OAuth2 client credentials, permission может отображаться в
standard OAuth scope без изменения её semantic name.

Internal API нельзя автоматически считать подмножеством public API. У них могут
отличаться callers, payload, rate limits и privacy requirements. Общие schema
components допустимы только при действительно одинаковом transport contract.

## Common client modules

Структура Gradle modules:

```text
clients/
  recipe/
    build.gradle.kts
  media/
    build.gradle.kts
  user/
    build.gradle.kts
```

Один client module соответствует одному callee и генерируется в:

```text
clients/<callee>/build/generated/openapi
```

Например:

```kotlin
dependencies {
    implementation(project(":clients:recipe"))
}
```

`clients/recipe` содержит generated API, transport DTO и standard client wiring.
Он не содержит Nutrition или Shopping business logic. Каждый caller создаёт
локальный anti-corruption adapter:

```text
services/nutrition/infrastructure/recipe/
  RecipeCatalogAdapter.kt
  RecipeClientMapper.kt
```

Общий client runtime отвечает за timeout, trace propagation, workload credential,
safe telemetry и error decoding. Retry включается per operation только при
заданной idempotency policy.

## Workload identity

### Required properties

Независимо от реализации workload credential содержит или криптографически
связывает:

- trusted issuer или certificate authority;
- stable workload subject;
- intended callee audience;
- short validity period;
- cryptographic signature or certificate chain.

Нельзя использовать как доказательство identity:

- `X-Caller-Service` или аналогичный caller-controlled header;
- source IP или DNS name;
- Kubernetes namespace без credential;
- shared API key для нескольких сервисов;
- end-user token вместо workload identity;
- network reachability без application authentication.

### Kubernetes projected token flow

При Kubernetes deployment каждый сервис получает отдельный ServiceAccount.
Caller использует bound projected short-lived token с audience callee:

```text
Nutrition pod
  ServiceAccount: nutrition
  token subject: system:serviceaccount:cookie:nutrition
  token audience: recipe

        Authorization: Bearer <workload-token>
                         |
                         v

Recipe validates:
  signature -> issuer -> validity -> audience=recipe -> subject=nutrition
```

Callee не принимает token с audience другого сервиса. Token rotation выполняет
platform/runtime, а credentials не попадают в repository, application config,
logs или error responses.

### SPIFFE/mTLS evolution

Если инфраструктура принимает SPIFFE-compatible identity, workload представляет
короткоживущий X.509 certificate, например:

```text
spiffe://cookie.production/service/nutrition
```

Callee проверяет certificate chain и SPIFFE ID во время mutual TLS. Semantic
permission и caller policy остаются теми же. Если TLS завершается на trusted
proxy, application принимает forwarded identity только по защищённому каналу от
этого proxy; внешний клиент не может напрямую установить identity header.

## User context

Service identity и user identity отвечают на разные вопросы:

- workload: какой сервис выполняет request;
- user: над данными какого пользователя выполняется действие.

Operation задаёт режим:

- `required` — caller обязан передать проверяемый on-behalf-of context;
- `optional` — system operation может выполняться без пользователя;
- `forbidden` — user context отклоняется, чтобы system job не маскировался под
  пользовательское действие.

User ID из обычного заголовка не считается context. Конкретный формат signed
context или token exchange выбирается в security implementation ADR.

## Enforcement order

Callee применяет проверки до business handler:

1. Route существует во внутреннем contract.
2. Credential присутствует для protected operation.
3. Проверены signature/certificate chain, issuer и validity.
4. Проверена audience текущего callee.
5. Workload subject преобразован в canonical service ID.
6. Permission operation найдена в service policy.
7. Caller входит в allowlist и существует в architecture model.
8. User context соответствует режиму operation.
9. Business authorization проверяет доступ к конкретному resource.

Отсутствующая или невалидная identity возвращает `401`. Подтверждённый, но не
разрешённый caller или context возвращает `403`. Ответ не раскрывает caller
allowlist или детали credential validation.

## Network boundaries

Public Caddy ingress маршрутизирует только public contract. Internal routes не
получают external route или public DNS entry.

Default-deny network policy генерируется из разрешённого graph и ограничивает:

- ingress callee только от разрешённых workloads;
- egress caller только к declared dependencies;
- internal service port отдельно от public ingress path, где это поддерживается.

Network policy является defense in depth на L3/L4 и не заменяет workload
authentication или operation authorization.

## Messaging permissions

Для NATS применяется независимый least-privilege allowlist:

```yaml
access:
  messaging:
    publish:
      - progress.day.updated
    subscribe:
      - nutrition.day.changed
```

Permissions генерируются из согласованных `model/events.yaml` и service
descriptor. Service credential не получает publish/subscribe `>` и не может
использовать event subjects, которых нет в architecture model.

## CI gates

CI должен блокировать merge, если:

1. Internal OpenAPI operation не имеет security policy или permission.
2. Permission из OpenAPI отсутствует в callee descriptor.
3. Caller в callee policy отсутствует в `model/services.yaml`.
4. Для локального grant нет `caller -> callee` в `sync-calls.yaml`.
5. Caller зависит от `clients/<callee>` без разрешённого sync edge.
6. Sync edge не реализован ни одной operation policy.
7. Client сгенерирован не из callee-owned contract.
8. Internal route попал в public gateway configuration.
9. NATS publish/subscribe permission отсутствует в event model.

Минимальные integration tests policy:

```text
allowed caller + correct audience + required user context -> allowed
unknown caller                                         -> 403
missing workload credential                            -> 401
wrong issuer, signature, expiry or audience             -> 401
missing required user context                           -> 403
unexpected user context for forbidden mode              -> 403
```

## Change workflow

Новый вызов `A -> C` добавляется одним pull request:

1. Обосновать edge и degradation behavior в `sync-calls.yaml`.
2. Добавить или изменить internal OpenAPI callee.
3. Добавить permission и caller в `services/C/service.yaml`.
4. Regenerate server transport и `clients/C`.
5. Подключить client и local adapter в caller.
6. Добавить timeout, authorization, degradation и contract tests.
7. Regenerate network and identity policy после появления deploy generator.

## References

- [OpenAPI security requirements](https://spec.openapis.org/oas/latest.html)
- [Kubernetes ServiceAccount identity](https://kubernetes.io/docs/concepts/security/service-accounts/)
- [Kubernetes NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
- [SPIFFE workload identities and SVIDs](https://spiffe.io/docs/latest/deploying/svids/)
- [NATS subject authorization](https://docs.nats.io/learn/security/authorization)
