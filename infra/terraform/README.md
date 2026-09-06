# Terraform

`modules/` содержит переиспользуемые модули, `environments/` — композицию для
конкретных окружений. Согласно ADR 0012, облачное окружение сейчас одно —
`production` в Yandex Cloud. `dev` остаётся локальным Docker Compose stack;
одноимённый Terraform-каталог является только безоблачным naming/validation
fixture. Постоянный `staging` не создаётся.

Production использует Terraform 1.16, закреплённый Yandex provider, partial S3
backend в private versioned Object Storage bucket и lockfile locking. Реальные
IDs, backend credentials, `backend.hcl`, `*.tfvars`, plans и state не
коммитятся. Bootstrap, обязательные inputs и ограничения описаны в
[`environments/production/README.md`](environments/production/README.md).

Локальная проверка без доступа к backend:

```bash
terraform fmt -check -recursive infra/terraform
terraform -chdir=infra/terraform/environments/dev init -backend=false
terraform -chdir=infra/terraform/environments/dev validate
terraform -chdir=infra/terraform/environments/production init -backend=false
terraform -chdir=infra/terraform/environments/production validate
```
