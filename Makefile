.PHONY: api-run identity-run test fmt openapi-generate compose-up compose-down terraform-fmt

GO_ENV = GOCACHE=$(CURDIR)/.cache/go-build GOENV=$(CURDIR)/.cache/go-env/goenv
COMPOSE ?= docker-compose

api-run:
	$(GO_ENV) go run ./apps/api/cmd/api

identity-run:
	./gradlew :backend:services:identity:bootRun --args='--spring.profiles.active=dev'

test:
	$(GO_ENV) go test ./apps/api/...
	./gradlew :backend:services:identity:check :backend:tools:notification-sink:check

fmt:
	gofmt -w $$(find apps/api -name '*.go' -type f)

openapi-generate:
	./gradlew :backend:services:identity:openApiGenerate :backend:services:identity:generateRuntimeOpenApi generateKmpPublicClient

compose-up:
	$(COMPOSE) -f deploy/docker/compose.yaml up --build

compose-down:
	$(COMPOSE) -f deploy/docker/compose.yaml down

terraform-fmt:
	terraform fmt -recursive infra/terraform
