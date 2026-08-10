# Agent instructions — Terraform

## Safety

- Never commit state, plans, credentials, private keys or real `tfvars`.
- Do not run `apply`, `destroy`, imports or state mutations unless the user
  explicitly requests the exact environment.
- Default diagnostic work is limited to `fmt`, `init -backend=false`, `validate`
  and read-only plans with known-safe inputs.
- Production changes require an accepted ADR and reviewed plan.

## Structure

- Put reusable resources in `modules/`; compose them in `environments/`.
- Pin providers when providers are introduced and commit the lock file only after
  the provider strategy is decided.
- Use remote encrypted state with locking before any shared environment exists.
- Expose minimal typed variables and document non-obvious outputs.
- Keep environment differences in inputs, not duplicated modules.

## Verification

```bash
terraform fmt -check -recursive infra/terraform
terraform -chdir=infra/terraform/environments/dev init -backend=false
terraform -chdir=infra/terraform/environments/dev validate
```
