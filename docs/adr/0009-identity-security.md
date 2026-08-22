# ADR 0009: безопасность Identity v1

- Status: accepted
- Date: 2026-08-19
- Amended: 2026-08-20

## Context

Identity v1 реализует только email/password. Контракт должен исключать
неподдержанные OAuth и account-management flows, не раскрывать наличие аккаунта
и сохранять возможность локально проверять access tokens во внутренних
сервисах. Durable messaging не должен превращать одноразовые секреты в
долгоживущие открытые данные.

## Decision

### Идентификаторы и credentials

- Все новые account, token, session и event IDs — UUIDv7.
- Email проходит trim, Unicode NFC, ASCII-проверку local-part, ICU UTS #46
  non-transitional IDNA-преобразование с STD3/Bidi/Context checks и lower-case.
  Разрешён только итоговый TLD `ru` или `xn--p1ai` (`.рф`). Provider-specific
  `+`/dot normalization запрещена.
- Пароль содержит 15–128 Unicode code points. Любые whitespace, Unicode
  separators и control characters запрещены. Пароль не нормализуется.
- Password hash — Argon2id PHC (`m=19456 KiB`, `t=2`, `p=1`, salt 16 bytes,
  output 32 bytes). Одновременно выполняемые Argon2 операции ограничиваются.

### Tokens

- Verification token: `v1.<UUIDv7 token id>.<256-bit random secret>`, в БД
  хранится SHA-256 verifier, TTL 30 минут, single use; resend отзывает прежние
  активные tokens и имеет cooldown 60 секунд.
- Refresh token: `v1.<UUIDv7 session id>.<256-bit random secret>`, SHA-256
  verifier, абсолютная жизнь family 30 дней, rotation при каждом refresh.
  Повтор ROTATED token отзывает family как replay. Logout идемпотентен и по
  любому валидному token отзывает всё family как одну logical device-session.
- Access JWT: ES256, `typ=at+jwt`, `iss=https://api.cookie.app`,
  `aud=cookie-api`, TTL 15 минут; claims `sub`, `sid`, `jti`, `iat`, `exp`.
  Email и роли не включаются. Public active/retiring keys доступны через JWKS
  с `max-age=300`; private key монтируется в deployable.

### Enumeration, abuse and messaging

Register/resend отвечают одинаковым `202` для существующих и неизвестных
адресов. Login возвращает одинаковый `401` для unknown/pending/locked/wrong:
для неизвестного account выполняется dummy Argon2 verification, для известного
проверяется реальный hash вне JDBC transaction. Правильный пароль во время
lockout не продлевает блокировку; только неверный пароль увеличивает backoff.

Password admission policy применяется при создании или смене credential, но не
при login: её последующее ужесточение не должно блокировать существующий
корректный hash. Login contract ограничивает только непустое значение и
безопасную максимальную длину. PostgreSQL-backed counters обеспечивают rate
limits между replicas; после пяти неверных паролей действует exponential
account backoff от 30 секунд до 15 минут.

Начальные лимиты: register `3/email/hour`, `20/IP/hour`; resend
`1/email/minute`, `5/email/hour`, `30/IP/hour`; login `10/email/15m`,
`100/IP/15m`; confirm `10/token/15m`, `60/IP/15m`; refresh и logout
`30/session/minute`, `120/IP/minute`. Scope values сохраняются как усечённые
SHA-256 digests, а не открытые email/IP/token identifiers.

Identity атомарно пишет state и transactional outbox. `account.activated` выходит
ровно один раз при переходе account в ACTIVE. Для
`notification.email.requested` открытый payload содержит только template,
expiry и compact JWE. Email, locale и verification token находятся внутри JWE
`RSA-OAEP-256` + `A256GCM` для публичного encryption key Notification Service.
Raw secrets не хранятся в outbox, JetStream и logs.

## Consequences

- Public auth contract v0.2 содержит register, confirm, resend, login, refresh,
  logout и JWKS; OAuth/reset/change/delete flows откладываются до реализации.
- Отсутствующее или невалидное signing/encryption key material не позволяет
  Identity стартовать; readiness работающего instance требует уже загруженных
  при startup ключей и PostgreSQL.
- Notification Service обязан управлять private decryption key и обрабатывать
  at-least-once delivery идемпотентно.
