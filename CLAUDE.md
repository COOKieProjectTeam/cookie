# Claude Code context — cookie

Read [AGENTS.md](AGENTS.md) first and follow the closest nested `AGENTS.md` for
the files being changed.

This is a new monorepo. The target stack is Kotlin/JVM backend services, Kotlin
Multiplatform mobile, PostgreSQL, NATS JetStream, Redis, Grafana and Terraform.
The current Go health-check is a migration stub, not the target architecture.
The neighboring `cookie-frontend`, `cookie-backend` and `architecture`
repositories are legacy references, not implementation sources.

Before editing:

1. Read `docs/architecture/README.md` and identify whether the change belongs to
   a domain service, mobile, a contract, infrastructure or an ADR.
2. Check `cookie-product` for an accepted requirement; candidates are not
   automatically approved scope.
3. Keep the smallest end-to-end change and run the scoped verification commands.

Never read raw interviews into logs or copy personal data into code, fixtures,
issues or commit messages.
