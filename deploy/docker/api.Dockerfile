FROM golang:1.26-alpine AS build
WORKDIR /src
COPY apps/api/go.mod ./apps/api/go.mod
COPY apps/api ./apps/api
RUN cd apps/api && CGO_ENABLED=0 go build -o /out/cookie-api ./cmd/api

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/cookie-api /cookie-api
EXPOSE 8080
ENTRYPOINT ["/cookie-api"]
