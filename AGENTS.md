# Agent instructions — cookie

## Repository purpose

This repository is the executable COOKie product monorepo. Keep Go API,
Kotlin Multiplatform client, API contracts, deployment files, Terraform and
technical ADRs here. Product interviews and product requirements belong in the
separate private `cookie-product` repository. Disposable integrations belong in
`cookie-labs`.

## Working rules

- Read the nearest nested `AGENTS.md` before changing a scoped directory.
- Keep changes atomic across API implementation, OpenAPI contract and consumers.
- Prefer standard-library Go until a dependency has a concrete product need.
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

Kotlin checks become mandatory after the Gradle/Xcode bootstrap is committed.

## Repository hygiene

- Keep `main` releasable; make changes through focused branches and pull requests
  once a remote exists.
- Update README and ADRs when repository boundaries or commands change.
- Generated code must identify its source and regeneration command.
- Avoid cross-application shared packages until at least two real consumers exist.
