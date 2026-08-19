.PHONY: api-run identity-run test fmt openapi-generate compose-up compose-down terraform-fmt

GO_ENV = GOCACHE=$(CURDIR)/.cache/go-build GOENV=$(CURDIR)/.cache/go-env/goenv

api-run:
	$(GO_ENV) go run ./apps/api/cmd/api

identity-run:
	./gradlew :services:identity:bootRun --args='--spring.profiles.active=dev'

test:
	$(GO_ENV) go test ./apps/api/...
	./gradlew :services:identity:check :tools:notification-sink:check

fmt:
	gofmt -w $$(find apps/api -name '*.go' -type f)

openapi-generate:
	./gradlew :services:identity:openApiGenerate :services:identity:generateRuntimeOpenApi generateKmpPublicClient

compose-up:
	docker compose -f deploy/docker/compose.yaml up --build

compose-down:
	docker compose -f deploy/docker/compose.yaml down

terraform-fmt:
	terraform fmt -recursive infra/terraform
