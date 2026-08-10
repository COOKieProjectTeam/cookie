.PHONY: api-run test fmt terraform-fmt

GO_ENV = GOCACHE=$(CURDIR)/.cache/go-build GOENV=$(CURDIR)/.cache/go-env/goenv

api-run:
	$(GO_ENV) go run ./apps/api/cmd/api

test:
	$(GO_ENV) go test ./apps/api/...

fmt:
	gofmt -w $$(find apps/api -name '*.go' -type f)

terraform-fmt:
	terraform fmt -recursive infra/terraform
