# Terraform

`modules/` содержит переиспользуемые модули, `environments/` — композицию для
конкретных окружений. Backend state и cloud provider добавляются после выбора
облака; секреты и `*.tfvars` не коммитятся.

Проверка локального каркаса:

```bash
terraform fmt -recursive infra/terraform
terraform -chdir=infra/terraform/environments/dev init -backend=false
terraform -chdir=infra/terraform/environments/dev validate
```
