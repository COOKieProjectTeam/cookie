.PHONY: api-run identity-run notification-run test fmt openapi-generate dev-build dev-build-identity dev-build-notification dev-build-notification-sink dev-up dev-down compose-up compose-down terraform-fmt architecture-setup architecture-build architecture-check architecture-serve

GO_ENV = GOCACHE=$(CURDIR)/.cache/go-build GOENV=$(CURDIR)/.cache/go-env/goenv
COMPOSE ?= $(shell if docker compose version >/dev/null 2>&1; then printf '%s' 'docker compose'; else printf '%s' 'docker-compose'; fi)

api-run:
	$(GO_ENV) go run ./apps/api/cmd/api

identity-run:
	COOKIE_DEV_NOTIFICATION_PRIVATE_KEY_OUTPUT_PATH="$${COOKIE_DEV_NOTIFICATION_PRIVATE_KEY_OUTPUT_PATH:-$(CURDIR)/.cache/dev/notification-private.jwk}" ./gradlew :backend:services:identity:bootRun --args='--spring.profiles.active=dev'

notification-run:
	COOKIE_NOTIFICATION_PRIVATE_KEY_PATH="$${COOKIE_NOTIFICATION_PRIVATE_KEY_PATH:-$(CURDIR)/.cache/dev/notification-private.jwk}" ./gradlew :backend:services:notification:bootRun --args='--spring.profiles.active=dev'

test:
	$(GO_ENV) go test ./apps/api/...
	$(GO_ENV) go vet ./apps/api/...
	./gradlew validatePlannedOpenApi validateServiceDescriptors :backend:platform:starter-web:check :backend:platform:starter-postgres:check :backend:platform:starter-messaging:check :backend:platform:starter-testing:check :backend:services:identity:check :backend:services:notification:check :backend:services:user:check :backend:tools:notification-sink:check compileKmpPublicClient

fmt:
	gofmt -w $$(find apps/api -name '*.go' -type f)

openapi-generate:
	./gradlew :backend:services:identity:openApiGenerate :backend:services:identity:generateRuntimeOpenApi :backend:services:notification:openApiGenerate :backend:services:notification:generateRuntimeOpenApi :backend:services:user:openApiGenerate :backend:services:user:generateRuntimeOpenApi bundlePublicOpenApi validateBundledPublicOpenApi validatePlannedOpenApi validateServiceDescriptors compileKmpPublicClient

dev-build: dev-build-identity dev-build-notification

dev-build-identity:
	$(COMPOSE) -f deploy/docker/compose.yaml build identity

dev-build-notification:
	$(COMPOSE) -f deploy/docker/compose.yaml build notification

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

ARCHITECTURE_PYTHON = .cache/architecture-explorer-venv/bin/python
ARCHITECTURE_REQUIREMENTS = docs/architecture/explorer/requirements.txt

.cache/architecture-explorer-venv/.requirements-installed: $(ARCHITECTURE_REQUIREMENTS)
	python3 -m venv .cache/architecture-explorer-venv
	$(ARCHITECTURE_PYTHON) -m pip install -r $(ARCHITECTURE_REQUIREMENTS)
	touch $@

architecture-setup: .cache/architecture-explorer-venv/.requirements-installed

architecture-build: architecture-setup
	$(ARCHITECTURE_PYTHON) docs/architecture/explorer/build.py

architecture-check: architecture-setup
	$(ARCHITECTURE_PYTHON) docs/architecture/explorer/build.py --check
	$(ARCHITECTURE_PYTHON) -m unittest discover -s docs/architecture/explorer/tests -p 'test_*.py'
	node --check docs/architecture/explorer/app.js
	node --test docs/architecture/explorer/tests/test_ui.cjs

architecture-serve:
	python3 -m http.server 8099 --bind 127.0.0.1 --directory docs/architecture/explorer
