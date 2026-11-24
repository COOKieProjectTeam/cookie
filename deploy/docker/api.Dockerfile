# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

FROM golang:1.27.4-alpine3.24@sha256:70bd7ddea8b6cbe5d6943cde279d6b67feb10cc924fb9100d1d8757c1575b280 AS build
WORKDIR /src
COPY apps/api/go.mod ./apps/api/go.mod
COPY apps/api ./apps/api
RUN --mount=type=cache,target=/root/.cache/go-build \
    cd apps/api && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/cookie-api ./cmd/api

FROM gcr.io/distroless/static-debian13:nonroot@sha256:516757106de35205efb70b91ba03f6f1298424a0678c603255cf2852a7000087
COPY --from=build /out/cookie-api /cookie-api
EXPOSE 8080
ENTRYPOINT ["/cookie-api"]
