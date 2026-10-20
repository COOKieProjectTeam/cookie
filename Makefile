.PHONY: api-run identity-run test fmt openapi-generate dev-build dev-build-identity dev-build-notification-sink dev-up dev-down compose-up compose-down terraform-fmt

GO_ENV = GOCACHE=$(CURDIR)/.cache/go-build GOENV=$(CURDIR)/.cache/go-env/goenv
COMPOSE ?= $(shell if docker compose version >/dev/null 2>&1; then printf '%s' 'docker compose'; else printf '%s' 'docker-compose'; fi)

api-run:
	$(GO_ENV) go run ./apps/api/cmd/api

identity-run:
	./gradlew :backend:services:identity:bootRun --args='--spring.profiles.active=dev'

test:
	$(GO_ENV) go test ./apps/api/...
	$(GO_ENV) go vet ./apps/api/...
	./gradlew validatePlannedOpenApi validateServiceDescriptors :backend:platform:starter-web:check :backend:platform:starter-postgres:check :backend:platform:starter-messaging:check :backend:platform:starter-testing:check :backend:services:identity:check :backend:tools:notification-sink:check compileKmpPublicClient

fmt:
	gofmt -w $$(find apps/api -name '*.go' -type f)

openapi-generate:
	./gradlew :backend:services:identity:openApiGenerate :backend:services:identity:generateRuntimeOpenApi bundlePublicOpenApi validateBundledPublicOpenApi validatePlannedOpenApi validateServiceDescriptors compileKmpPublicClient

dev-build: dev-build-identity dev-build-notification-sink

dev-build-identity:
	$(COMPOSE) -f deploy/docker/compose.yaml build identity

dev-build-notification-sink:
	$(COMPOSE) -f deploy/docker/compose.yaml build notification-sink

dev-up:
	$(COMPOSE) -f deploy/docker/compose.yaml up --build

dev-down:
	$(COMPOSE) -f deploy/docker/compose.yaml down

compose-up: dev-up

compose-down: dev-down

terraform-fmt:
	terraform fmt -recursive infra/terraform
