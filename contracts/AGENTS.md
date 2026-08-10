# Agent instructions — API contracts

- Keep OpenAPI valid and reviewable; avoid generated noise.
- Operation IDs and schema names are stable public identifiers.
- Mark breaking changes explicitly in the pull request and coordinate client
  migration in the same change when possible.
- Examples must contain synthetic data only.
- Update the server implementation and contract tests together.
