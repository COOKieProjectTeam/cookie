# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

FROM golang:1.27.2-alpine3.24@sha256:f92b6ef800e499660581efdabdf25d9d817a9d124eaf900924f0504e7e27e12d AS build
WORKDIR /src
COPY apps/api/go.mod ./apps/api/go.mod
COPY apps/api ./apps/api
RUN --mount=type=cache,target=/root/.cache/go-build \
    cd apps/api && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/cookie-api ./cmd/api

FROM gcr.io/distroless/static-debian13:nonroot@sha256:1c2c046bc09ed40fad370b599a0b1ae7987f55b01e247cf27a7c27cd97e5bbc7
COPY --from=build /out/cookie-api /cookie-api
EXPOSE 8080
ENTRYPOINT ["/cookie-api"]
