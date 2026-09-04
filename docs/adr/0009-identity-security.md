# ADR 0009: безопасность Identity v1

- Status: accepted
- Date: 2026-08-19
- Amended: 2026-09-03

## Context

Identity v1 реализует только email/password. Контракт должен исключать
неподдержанные OAuth и account-management flows, не раскрывать наличие аккаунта
и сохранять возможность локально проверять access tokens во внутренних
сервисах. Durable messaging не должен превращать одноразовые секреты в
долгоживущие открытые данные.

## Decision

### Идентификаторы и credentials

- Все выдаваемые сервером account, token, session и event IDs — UUIDv7.
  `registrationAttemptId` является криптографически случайным UUID, который
  создаёт клиент как стабильный idempotency/correlation key одного flow.
- `CanonicalEmail` отвечает только за структуру и каноническое представление:
  trim, ASCII-проверку local-part, ICU UTS #46 non-transitional
  IDNA-преобразование домена с STD3/Bidi/Context checks и lower-case. Отдельная
  NFC-нормализация всего адреса не выполняется: local-part ограничен ASCII, а
  Unicode-домен нормализуется UTS #46. Provider-specific `+`/dot normalization
  запрещена. В рамках поддерживаемых `.ru`/`.рф` providers продукт считает
  ASCII local-part регистронезависимым и хранит его в lower-case; mailbox,
  который различает регистр local-part, этим контрактом не поддерживается.
- Policy приёма новой регистрации отдельно разрешает только итоговый TLD `ru`
  или `xn--p1ai` (`.рф`). Login, resend и reconstitution применяют только
  структурные правила, поэтому изменение admission policy не делает ранее
  сохранённый credential недоступным.
- При регистрации пароль содержит 15–128 Unicode code points после NFC.
  Login принимает 1–128 code points для совместимости с ранее созданными
  credentials и применяет ту же NFC перед Argon2 verification. Обычные пробелы,
  Unicode и emoji разрешены; ISO control и line/paragraph separators запрещены.
  Требования к регистру, цифрам или специальным символам не вводятся.
- Password hash — Argon2id PHC (`m=19456 KiB`, `t=2`, `p=1`, salt 16 bytes,
  output 32 bytes). Одновременно выполняемые Argon2 операции ограничиваются.

### Tokens

- Регистрация моделируется aggregate root `RegistrationAttempt`; `Account` до
  подтверждения владения email не существует. Root живёт 24 часа, один раз
  хранит Argon2id hash предложенного пароля, proof verifier, request fingerprint
  и locale; каждое письмо представлено дочерним `RegistrationVerificationToken`.
  Fingerprint — domain-separated HMAC-SHA-256 полного канонического запроса с
  `registrationProof` как ключом: он выявляет изменение password/locale при
  retry, но не превращает утечку БД в быстрый offline password verifier.
  Email token имеет вид
  `v1e.<client registrationAttemptId>.<UUIDv7 token id>.<256-bit random secret>`.
  Attempt id публичен и позволяет deep-link выбрать правильный proof из
  защищённого хранилища; оба секрета всё равно обязательны для создания Account.
  Победивший attempt атомарно создаёт Account, redeem-ит предъявленного child,
  стирает свой pending password hash/locale. Проигравшие pending attempts того
  же email в той же транзакции переводятся в `abandoned`: их password hash/locale
  тоже стираются, но id/proof fingerprint/token children остаются bounded
  idempotency evidence.
- Каждый email token живёт 30 минут и single-use относительно aggregate. Resend
  имеет cooldown 60 секунд, добавляет child в тот же root, наследует locale и не
  отзывает прежние unexpired tokens: outbox/JetStream не обещают порядок доставки,
  поэтому позднее старое письмо остаётся рабочим. Истечение root проверяется
  доменом и не зависит от расписания retention. Maintenance атомарно переводит
  истёкший незавершённый root в `abandoned` и стирает password hash/locale вместо
  немедленного удаления. Completed и abandoned tombstones хранятся не менее 30
  дней, поэтому cleanup не превращает exact register retry в новую отправку.
  `activatedAccountId` в completed tombstone — логическая audit-ссылка без SQL
  FK: будущее privacy deletion Account не блокируется и не сокращает этот срок.
  Точный retry погашенной пары token+proof является no-op success не менее 30
  дней; затем audit tombstone удаляется, и пара снова недействительна. Confirm
  не создаёт session: клиент выполняет обычный login отдельным запросом, поэтому
  потерянный HTTP response не приводит к невосстановимому refresh secret.
- `RefreshFamily` — aggregate root и одна logical device-session;
  `RefreshCredential` — его одноразовый credential. Family владеет account,
  device, абсолютным 30-дневным expiry и состоянием revoke, а credential —
  verifier и фактом redemption/replacement. В PostgreSQL максимум один
  unredeemed credential на family, а replacement не может перейти в другую
  family.
- Refresh token имеет вид `v1.<UUIDv7 credential id>.<256-bit secret>`. Первый
  secret криптографически случаен. Successor детерминированно выводится через
  HMAC-SHA-256 из secret предъявленного predecessor, predecessor/replacement IDs
  и `Idempotency-Key`; в БД остаются только SHA-256 verifier и rotation metadata.
  Это позволяет в течение 30 секунд вернуть тот же successor для точного retry,
  пока он остаётся current, не сохраняя raw token или HTTP response. Точный retry
  с тем же key после окна или после следующей rotation отклоняется без отзыва.
  Только предъявление redeemed credential с другим key считается token reuse и
  атомарно отзывает family. Logout идемпотентен и по любому криптографически
  валидному credential отзывает family.
- Access JWT: ES256, `typ=at+jwt`, `iss=https://api.cookie.app`,
  `aud=cookie-api`, TTL 15 минут; claims `sub`, `sid`, `jti`, `iat`, `exp`.
  `sid` содержит стабильный `RefreshFamily.id` и не меняется при rotation; email
  и роли не включаются. Public active/retiring keys доступны через JWKS с
  `max-age=300`; private key монтируется в deployable.

### Enumeration, abuse and messaging

Register/resend отвечают одинаковым `202` для существующих и неизвестных
адресов. Login возвращает одинаковый `401` для unknown/locked/wrong:
для неизвестного account выполняется dummy Argon2 verification, для известного
проверяется реальный hash вне JDBC transaction. Пока lockout активен, ни
правильный, ни неверный пароль не меняет counter и не продлевает блокировку;
новый неверный пароль увеличивает backoff только после окончания текущего окна.

Password admission policy применяется при создании или смене credential, но не
при login: её последующее ужесточение не должно блокировать существующий
корректный hash. Login contract ограничивает только непустое значение и
безопасную максимальную длину. PostgreSQL-backed counters обеспечивают rate
limits между replicas; после пяти неверных паролей действует exponential
account backoff от 30 секунд до 15 минут.

Начальные лимиты: register `3/email/hour`, `20/IP/hour`; resend
`1/email/minute`, `5/email/hour`, `30/IP/hour`; login `10/email/15m`,
`100/IP/15m`; confirm `10/token/15m`, `60/IP/15m`; refresh и logout
`30/family/minute`, `120/IP/minute`. Scope values сохраняются как усечённые
SHA-256 digests, а не открытые email/IP/token identifiers.
IP bucket списывается первой application-операцией для каждого уже декодированного
auth mutation, включая domain-invalid значения и точный retry: злоумышленник не
должен бесплатно нагружать domain parsing или основные aggregate queries. Синтаксически
невалидный JSON, медленные соединения и запросы, отклонённые transport validation до
входа в use case, дополнительно ограничиваются ingress/gateway; application limiter
не является DDoS-периметром. Точный уже сохранённый register retry и resend внутри
cooldown обходят только email business bucket, а повтор успешно погашенной пары
confirm — только token bucket. Поэтому идемпотентный повтор не дублирует бизнес-эффект,
но всё ещё может получить `429` от общего abuse ceiling. Неизвестные и несовпадающие
запросы дополнительно списывают соответствующие email/token scopes.
Credential/family bucket для refresh и logout списывается только после
constant-time проверки verifier; IP bucket остаётся первым. Поэтому знание
credential UUID или `sid` из access JWT не позволяет исчерпать чужой лимит
запросами с выдуманным secret.

Rate limiting использует непосредственный peer address. `X-Forwarded-For`
разбирается справа налево только если непосредственный peer входит в явно
настроенный `COOKIE_IDENTITY_TRUSTED_PROXY_CIDRS`; от любого другого клиента
forwarding headers полностью игнорируются. Тела mutation-запросов ограничены
16 KiB до JSON parsing, включая chunked requests.

Доменное `now` для account, registration attempt и refresh family читается через output
port из PostgreSQL `clock_timestamp()` уже внутри state-changing transaction.
Тот же момент передаётся в access-token adapter. Это задаёт единый порядок
событий между репликами и не позволяет clock skew представить сохранённое
состояние как созданное в будущем. Outbox timestamps и retention cutoffs также
используют этот источник; локальные часы процесса остаются только у UUIDv7 и
приблизительного HTTP `Retry-After`.

Переданный при регистрации `locale` разбирается строго как BCP 47 language tag
и канонизируется до записи в notification payload. Максимум 255 ASCII-символов
является защитным product/storage bound, а не ограничением BCP 47. Domain и
application validation используют типизированные причины; HTTP adapter
преобразует их в стабильные публичные error code/message и не возвращает
произвольный текст исключения.

Identity атомарно пишет state и transactional outbox. `account.activated` выходит
ровно один раз при создании подтверждённого Account. Для
`notification.email.requested` открытый payload содержит только template,
  expiry и compact JWE. Attempt id, email, locale и verification token находятся внутри JWE
`RSA-OAEP-256` + `A256GCM` для публичного encryption key Notification Service.
Raw secrets не хранятся в outbox, JetStream и logs.

## Consequences

- Public auth contract v0.6 содержит register, confirm, resend, login, refresh,
  logout и JWKS; OAuth/reset/change/delete flows откладываются до реализации.
- Refresh является идемпотентным только для того же predecessor token и того же
  обязательного UUID `Idempotency-Key` в фиксированном 30-секундном окне. Новый
  access JWT при воспроизводимом retry может отличаться, но successor refresh
  token совпадает. Устаревший точный retry получает `401`, не отзывая family.
- Отсутствующее или невалидное signing/encryption key material не позволяет
  Identity стартовать; readiness работающего instance требует уже загруженных
  при startup ключей и PostgreSQL.
- Notification Service обязан управлять private decryption key и обрабатывать
  at-least-once delivery идемпотентно.
