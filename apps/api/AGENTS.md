# Agent instructions — Go API

## Scope

These rules apply to `apps/api/`.

## Design

- Keep entry points in `cmd/` and non-public implementation in `internal/`.
- Organize new code by business capability, not by generic controller/service
  buckets.
- Use `net/http` conventions and explicit dependency injection.
- Pass `context.Context` through request-scoped work.
- Add timeouts to network clients and servers.
- Return stable JSON errors; never expose internal errors or secrets.
- Treat `contracts/openapi/openapi.yaml` as the external API contract.

## Tests

- Add table-driven unit tests for domain behavior.
- Test handlers with `httptest`.
- Prefer fakes at owned boundaries; use integration tests for databases and
  external protocols when they are introduced.
- A bug fix must include a failing regression test where practical.

## Commands

```bash
make test
make fmt
GOCACHE="$PWD/.cache/go-build" GOENV="$PWD/.cache/go-env/goenv" go vet ./apps/api/...
```
