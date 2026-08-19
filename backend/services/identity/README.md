# Identity Service

Identity v1 supports email/password registration, email confirmation, login,
refresh rotation, idempotent logout and ES256 JWKS. The public transport is
generated from the service-owned `contracts/openapi/public/identity.yaml`; generated
sources live under `build/generated` and are never committed.

## Local run

The complete development stack is started from the repository root:

```bash
make compose-up
```

- API: `http://localhost:8080/v1/auth/...`
- liveness/readiness: `http://localhost:8080/healthz`, `/readyz`
- Mailpit UI: `http://localhost:8025`
- NATS monitoring: `http://localhost:8222`

Identity never sends email directly. It writes an encrypted
`notification.email.requested` event to its transactional outbox; the local
Notification sink decrypts the compact JWE and delivers it to Mailpit.

## Key configuration

Without the `dev` or `test` Spring profile, Identity requires mounted JSON Web
Key files and never generates or writes key material:

- `COOKIE_IDENTITY_JWT_PRIVATE_KEY_PATH`: private P-256 JWK with `kid`;
- `COOKIE_IDENTITY_JWT_RETIRING_PUBLIC_KEY_PATHS`: comma-separated public P-256
  JWK files retained during verification-key rotation;
- `COOKIE_NOTIFICATION_PUBLIC_KEY_PATH`: Notification Service RSA public JWK.

Private signing/decryption material must not be committed. Ephemeral generation
and `COOKIE_DEV_NOTIFICATION_PRIVATE_KEY_OUTPUT_PATH` are available only in the
`dev` and `test` profiles; local compose enables `dev` explicitly.
