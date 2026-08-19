# Agent instructions — backend migration stub

## Scope

These rules apply to `apps/api/`. This directory currently contains the legacy
Go health-check bootstrap. The accepted target is Kotlin/JVM backend services;
see `docs/adr/0003-kotlin-backend.md` and `docs/architecture/`.

## Migration rule

- Do not add product behavior to the Go implementation.
- Preserve the health-check only until the Kotlin runtime replaces it.
- New service boundaries and dependencies must match
  `docs/architecture/model/services.yaml`.
- Public HTTP behavior must match the owning service contract under
  `contracts/openapi/public/`; Kotlin routing contracts, server interfaces and
  transport models are generated according to `contracts/openapi/generation.yaml`.
- Do not handwrite routes or DTOs that duplicate generated transport code.
- Each deployable Kotlin component implements its own generated `/healthz` and
  `/readyz` runtime interfaces. Never route these probes to Health Data Service.
- Published and consumed events must match
  `docs/architecture/model/events.yaml`.
- Every stateful service must implement the outbox/inbox protocol from
  `docs/architecture/messaging.md`.

## Temporary verification

```bash
make test
make fmt
GOCACHE="$PWD/.cache/go-build" GOENV="$PWD/.cache/go-env/goenv" go vet ./apps/api/...
```
