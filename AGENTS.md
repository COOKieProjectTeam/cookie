# Agent instructions — cookie

## Repository purpose

This repository is the executable COOKie product monorepo. Keep Kotlin/JVM
backend services, the Kotlin Multiplatform client, API/event contracts,
deployment files, Terraform and technical ADRs here. Product interviews and
product requirements belong in the separate private `cookie-product`
repository. Disposable integrations belong in `cookie-labs`.

## Working rules

- Read the nearest nested `AGENTS.md` before changing a scoped directory.
- Read `docs/architecture/README.md` and its YAML model before changing service
  boundaries, events, synchronous dependencies or infrastructure usage.
- Keep changes atomic across API implementation, OpenAPI contract and consumers.
- New backend implementation is Kotlin/JVM. Do not extend the Go bootstrap with
  product behavior; migrate or replace it.
- Every stateful domain service uses PostgreSQL transactional outbox and
  idempotent inbox around NATS JetStream delivery.
- Redis is not a system of record and may only be used for an explicitly
  documented ephemeral use case.
- Do not add secrets, `.env` files, state files, signing material or production
  identifiers.
- Record durable technical choices in `docs/adr/`.
- Do not revive Next.js or ASP.NET conventions from the legacy repositories.
- Do not copy interview transcripts or respondent data into this repository.

## Verification

Run checks relevant to the changed area:

```bash
make test
GOCACHE="$PWD/.cache/go-build" GOENV="$PWD/.cache/go-env/goenv" go vet ./apps/api/...
terraform fmt -check -recursive infra/terraform
terraform -chdir=infra/terraform/environments/dev validate
docker-compose -f deploy/docker/compose.yaml config
```

Backend and mobile Kotlin checks become mandatory after their Gradle bootstraps
are committed. Go checks remain temporary while the bootstrap exists.

## Repository hygiene

- Keep `main` releasable; make changes through focused branches and pull requests
  once a remote exists.
- Use the configured human Git identity. Do not add tool/AI attribution or
  automated co-author trailers to commit metadata.
- Update README and ADRs when repository boundaries or commands change.
- Generated code must identify its source and regeneration command.
- Avoid cross-application shared packages until at least two real consumers exist.
