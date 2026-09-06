# ADR 0011: асинхронное удаление аккаунта

- Status: accepted
- Date: 2026-09-04

## Context

Удаление пользовательских данных принадлежит нескольким сервисам и не может
быть одной синхронной SQL-операцией Identity. HTTP-ответ должен подтверждать
только принятие команды, немедленно закрывать возможность получить новую
сессию и запускать durable event flow. Он не должен утверждать, что данные уже
удалены, пока каждый владелец данных не подтвердил локальную очистку.

Первая версия реализует только начало процесса. Downstream consumers,
acknowledgement events и финализация Identity ещё не реализованы, поэтому
единственный честный итоговый статус этой версии — `DELETION_PENDING`.

## Decision

### Public command and lifecycle

Identity принимает `POST /v1/auth/account-deletion-requests`. Операция требует:

- проверенный access JWT в `Authorization: Bearer`;
- текущий пароль как повторное подтверждение личности;
- обязательный криптографически случайный RFC 4122 UUIDv4
  `Idempotency-Key` одной логической команды.

Идентификатор аккаунта берётся только из проверенного `sub`; body, query и
доверительные headers не могут его переопределить. Устройство, на котором была
создана session, не участвует в авторизации удаления.

Успех возвращает `202 Accepted` с исходными `deletionRequestId`,
`status=DELETION_PENDING` и `requestedAt`. Переход аккаунта в v1 единственный и
необратимый:

```text
ACTIVE -> DELETION_PENDING
```

Grace period, cancellation и переход в `DELETED` отсутствуют. Физическая строка
Account, canonical email и Argon2id password hash не удаляются и не очищаются,
пока распределённая очистка не будет реализована и подтверждена. Поэтому новая
регистрация с тем же email остаётся запрещена обычной уникальностью credential.

### Authentication and abuse protection

Identity становится resource service для этой операции. Проверка access JWT
обязана подтвердить ES256 signature одним из active/retiring Identity keys,
разрешённый algorithm, точный `typ=at+jwt`, configured issuer и audience,
`iat`, `exp`, валидный UUID в `sub` и назначение токена как access token.
Истёкший, malformed или иначе неподходящий token не даёт account context.

До Argon2 verification применяются PostgreSQL-backed лимиты:

- `30/IP/hour` до дорогой проверки password hash;
- `10/account/hour` после успешной аутентификации JWT.

Оба scope используют существующий domain-separated HMAC-SHA-256 механизм.
Account identifier хешируется в новом namespace `account`; открытые IP,
account id и credential material не записываются в rate-limit key. Password,
access JWT и `Idempotency-Key` не попадают в logs, telemetry или events.

### Idempotency and transaction

После прохождения authentication, validation, rate-limit и availability gates один
account и один key возвращают один и тот же принятый результат. Retry всё
ещё может получить `429` или retryable `503`; клиент сохраняет тот же key
и повторяет после указанной задержки. Конкурентные запросы с тем же key не
создают вторую запись или событие.
Запросы с разными keys для одного account сходятся к уже существующему active
deletion request и также возвращают `202`, не публикуя событие повторно.
Неверный пароль не создаёт idempotency evidence.

Гарантия опирается одновременно на account row lock, уникальность
`(account_id, idempotency_key)` и partial unique index одного active
`DELETION_PENDING` request на account. Пароль или его производные никогда не
используются как request fingerprint.

Чтобы не держать PostgreSQL lock во время Argon2, application может проверить
наблюдаемый password hash до state-changing transaction. После получения lock
она обязана повторно сравнить hash и account state с наблюдённой версией;
изменившийся credential нельзя принимать по старому результату проверки.

Единая локальная transaction использует следующий порядок блокировок и записи:

1. account root;
2. существующий deletion request или его uniqueness keys;
3. refresh-family rows аккаунта;
4. immutable outbox row.

Ни один flow не должен, удерживая family lock, затем запрашивать account lock:
account всегда находится раньше family в глобальном lock order.

В этой transaction Identity повторно проверяет актуальный state/hash, создаёт
или находит deletion request, переводит Account в `DELETION_PENDING`, отзывает
все refresh families и записывает integration event. Любая ошибка откатывает
все четыре эффекта. `requestedAt`, state timestamps и `occurred_at` используют
PostgreSQL-backed clock согласно ADR 0009.

### Messaging and completion boundary

Identity записывает ровно один logical event:

```text
event_type: account.deletion.requested
event_version: 1
subject: cookie.events.account.deletion.requested.v1
```

Обязательные `event_id` и `occurred_at` находятся в общем envelope. PII-free
business payload содержит только `accountId`, `deletionRequestId` и
`requestedAt`. Broker delivery остаётся at-least-once; будущие consumers
обязаны применять событие идемпотентно через собственный PostgreSQL inbox.

Target data owners: User, Food Catalog, Nutrition, Recipe, Shopping, Health,
Progress, Notification, Media и Meal Planner. Они перечислены в архитектурной
модели, но их consumers и acknowledgement events в этой версии не существуют.
Identity по-прежнему ничего не потребляет и не получает фиктивный inbox.
Событие `account.deleted` не публикуется. Account остаётся
`DELETION_PENDING`, пока следующий этап не определит acknowledgements,
reconciliation и финализацию с очисткой email/password hash.

Production retention/replay обязан учитывать, что consumer, созданный позже
broker retention window, не увидит старое событие автоматически. До включения
финализации нужен отдельный reconciliation/replay путь для всех сохранённых
pending requests; повторное изображение HTTP idempotency как нового события
таким механизмом не является.

### Access-token residual window

Отзыв refresh families запрещает login/refresh и выпуск новых bearer tokens, но
не отзывает уже подписанный access JWT. Без denylist или introspection он остаётся
криптографически пригодным до `exp`: остаточное окно составляет до фактического
настроенного access-token TTL (15 минут по умолчанию; конфигурация сейчас
допускает до 24 часов). Клиент после подтверждённого `202` удаляет локальные access/refresh
credentials, но это не сокращает server-side окно и не является его гарантией.

### Rolling deployment

Миграция является additive: новый `accounts.status` получает
`NOT NULL DEFAULT 'ACTIVE'`, поэтому старый binary продолжает вставлять Account,
не зная новой колонки. Public route нельзя включать и отдавать mobile client,
пока все старые Identity pods не выведены: они не реализуют route и вернут 404.
Статусный CHECK добавляется `NOT VALID`: константный default делает все старые
строки заведомо валидными, а constraint сразу проверяет все новые INSERT/UPDATE.
Это исключает validation scan под `ACCESS EXCLUSIVE` в V004; отдельная поздняя
контролируемая миграция обязана выполнить `VALIDATE CONSTRAINT` с подходящим timeout.

Дополнительно deletion transaction записывает far-future `locked_until` в
email credential. Старый binary ещё не читает `accounts.status`, но уже понимает
активный lock и поэтому отвечает обычным invalid-credentials вместо выдачи новой
session. Account state, а не этот compatibility lock, остаётся канонической
причиной запрета в новом коде.

В v1 отзыв family сохраняет существующую DB reason `LOGOUT`. Добавление нового
`ACCOUNT_DELETION` сломало бы старый `RefreshFamilyRevokeReason.valueOf` при
mixed-version чтении. Каноническую причину процесса задаёт Account status;
расширять stored revoke enum можно только отдельным двухфазным rollout после
того, как все readers научатся принимать новое значение.

## Consequences

- HTTP wire change аддитивное; public Identity contract получает minor-version,
  а существующие operation IDs и schemas не меняются.
- Identity немедленно прекращает новые login/refresh sessions и атомарно
  сохраняет durable запрос, но не заявляет о завершённой privacy deletion.
- NATS publish policy deployment должна разрешить новый exact subject;
  wildcard development stream уже совместим.
- Отсутствие downstream acknowledgements, reconciliation и access-token
  revocation остаётся явным ограничением первой версии, а не скрытым success.
- Следующий этап реализует inbox consumers владельцев данных, acknowledgement
  protocol, безопасный replay pending requests и только затем финализацию
  Identity с очисткой canonical email и password hash.
