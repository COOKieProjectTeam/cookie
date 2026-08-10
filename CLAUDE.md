# Claude Code context — cookie

Read [AGENTS.md](AGENTS.md) first and follow the closest nested `AGENTS.md` for
the files being changed.

This is a new monorepo. The active stack is Go + Kotlin Multiplatform +
Terraform. The neighboring `cookie-frontend`, `cookie-backend` and `architecture`
repositories are legacy references, not implementation sources.

Before editing:

1. Identify whether the change belongs to API, mobile, contract, infrastructure
   or an ADR.
2. Check `cookie-product` for an accepted requirement; candidates are not
   automatically approved scope.
3. Keep the smallest end-to-end change and run the scoped verification commands.

Never read raw interviews into logs or copy personal data into code, fixtures,
issues or commit messages.
