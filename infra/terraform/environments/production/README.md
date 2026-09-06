# Production infrastructure

Этот каталог описывает минимальный **production-only** контур COOKie в Yandex
Cloud. Он создаёт одну обычную (не preemptible) VM (2 vCPU, 4 GiB RAM, 50%
baseline), отдельный persistent data disk, VPC с subnet в каждой действующей
regular-зоне (`a`, `b`, `d`, `e`), статический IPv4, Container Registry,
раздельные service account для push/pull образов и временный API Gateway с
Yandex-managed HTTPS-доменом.

Это намеренно не HA-топология: VM, PostgreSQL и NATS остаются единой точкой
отказа. Перед фактическим production apply необходимы accepted ADR, reviewed
plan, стратегия резервного копирования/восстановления и бюджетные оповещения.

## Безопасность по умолчанию

- Security Group принимает TCP/22 только из обязательного узкого allowlist
  (`/24` или уже; обычно один адрес VPN/оператора как `/32`). TCP/8080 принимает
  только внутренний source CIDR API Gateway `198.19.0.0/16`; других ingress rules
  для приложения, PostgreSQL или NATS нет.
- API Gateway публикует только `/v1/auth/{path+}` и передаёт Identity все headers
  и query parameters, кроме присланного клиентом `X-Forwarded-For`: он явно
  удаляется до HTTP integration. `/healthz`, `/readyz`, PostgreSQL и NATS
  отсутствуют в его OpenAPI specification. До появления собственного домена и
  Caddy gateway предоставляет usable HTTPS на своём service domain.
- Для работы gateway задайте в production Compose
  `COOKIE_IDENTITY_BIND_IP=0.0.0.0`,
  `COOKIE_IDENTITY_TRUSTED_PROXY_CIDRS=198.19.0.0/16`, а
  `COOKIE_IDENTITY_ISSUER` возьмите из output `identity_issuer_url`. Прямой
  операторский доступ к 8080 остаётся возможен только через SSH tunnel из
  output `ssh_tunnel_command`.
- VM получает `container-registry.images.puller`, а GitHub Actions —
  `container-registry.images.pusher` только на repository `identity`, не на весь
  registry. Роль pusher также позволяет изменять и удалять образы внутри этого
  repository, поэтому каждый будущий сервис должен получить отдельный repository
  и отдельную IAM-привязку.
- GitHub не хранит authorized/static key. Federated credential принимает лишь
  точный OIDC subject из обязательной переменной `github_oidc_subject`, всегда с
  `:ref:refs/heads/main`. Используйте immutable owner/repository-ID форму,
  которую GitHub фактически возвращает в `sub`, если она включена для repo.
- cloud-init содержит только публичный SSH-ключ. Runtime secrets, TLS-ключи и
  production `.env` должны поступать отдельным секретным процессом в
  `/etc/cookie`; Terraform ими не управляет.
- Persistent disk монтируется в `/srv/cookie`, защищён `prevent_destroy` и не
  удаляется вместе с VM. Для намеренного удаления потребуется отдельное
  проверяемое изменение Terraform.

## Remote state

Backend является partial-конфигурацией. Bucket создаётся отдельным bootstrap
процессом, потому что окружение не может надёжно хранить собственный backend в
своём же state. Bucket должен быть private, иметь versioning и default
server-side encryption с Yandex KMS key. Это bucket-level правило обязательно:
стандартный S3 backend с одним `encrypt = true` отправляет AWS `AES256`, тогда
как Object Storage ожидает `aws:kms`, а его Yandex key ID не является AWS ARN.
Поэтому backend не переопределяет алгоритм каждого PUT, и bucket шифрует state и
lock-файл своим KMS key по умолчанию.

Значение `bucket` в `versions.tf` — намеренно несуществующий placeholder: начиная
с Terraform 1.15, `validate` проверяет обязательные поля S3 даже после
`init -backend=false`. Для любого реального init его обязательно заменяет
`backend.hcl`.

Terraform 1.16 использует нативный S3 lock-файл
`cookie/production/terraform.tfstate.tflock` (`use_lockfile = true`). Это замена
устаревающему DynamoDB/YDB locking. Backend identity нужны минимальные права:

- list bucket только для prefix `cookie/production/`;
- get/put state object;
- get/put/delete только `.tflock`;
- encrypt/decrypt через выбранный KMS key.

Перед первым совместным запуском проверьте locking двумя параллельными
`terraform plan`/`apply` в безопасном bootstrap-проекте; до такой проверки
допускается только один state writer. Никогда не используйте `-lock=false`.

```bash
cp backend.hcl.example backend.hcl
cp terraform.tfvars.example terraform.tfvars

export AWS_ACCESS_KEY_ID="<s3-access-key-id>"
export AWS_SECRET_ACCESS_KEY="<s3-secret-key>"
# Set AWS_SESSION_TOKEN only when the chosen temporary credentials include it.
export YC_TOKEN="$(yc iam create-token)"

terraform init -backend-config=backend.hcl
terraform fmt -check
terraform validate
terraform plan -out=production.tfplan
```

Не сохраняйте credentials, `backend.hcl`, `terraform.tfvars`, state или plan в
Git. Для локальной синтаксической проверки без доступа к cloud backend:

```bash
terraform init -backend=false
terraform validate
```

## GitHub OIDC bootstrap

Federation и federated credential впервые создаются под временной человеческой
или bootstrap identity с правами на IAM. После apply workflow запрашивает GitHub
OIDC token со следующими значениями из outputs:

- `audience`: `github_oidc_audience`;
- token-exchange `audience`: `ci_registry_pusher_service_account_id`;
- exact `sub`: `github_oidc_subject` (не конструируйте его из отображаемых имён;
  скопируйте фактический claim, предпочтительно с immutable IDs).

До включения credential защитите ветку `main`: запретите force-push/delete и
direct push, потребуйте pull request, review и все CI checks. Иначе любой, кто
может напрямую изменить workflow в `main`, сможет запросить тот же разрешённый
OIDC subject и управлять образами в Identity repository.

JWT обменивается на короткоживущий IAM token через
`https://auth.yandex.cloud/oauth/token`. Credential для tag, pull request,
другой ветки или GitHub Environment не совпадёт с разрешённым subject.

## VM bootstrap and data

VM создаётся из актуального семейства `ubuntu-2604-lts`. cloud-init:

1. проверяет существующую ext4 на диске `cookie-data` и монтирует её в
   `/srv/cookie` по filesystem UUID; неизвестная файловая система, пустой диск
   или ошибка чтения останавливают bootstrap до установки Docker;
2. устанавливает Docker Engine, containerd, Buildx и Compose plugin из
   официального stable apt-репозитория Docker, а также требуемые deploy script
   утилиты `curl`, `jq` и `flock` (`util-linux`);
3. создаёт `/opt/cookie`, `/etc/cookie`, `/srv/cookie/postgres` (`70:70`,
   `0750`) и `/srv/cookie/nats` (`1000:1000`, `0750`) без runtime secrets;
4. отключает SSH password/root login и включает ограниченную ротацию Docker
   logs.

Bootstrap никогда не форматирует диск. Первичная инициализация нового диска —
отдельное однократное действие оператора; пересоздание VM не разрешает его
повторно.

После создания VM подключитесь к ней и дождитесь завершения cloud-init:

```bash
ssh "$(terraform output -raw production_vm_ssh_user)@$(terraform output -raw production_vm_public_ip)"
cloud-init status --wait
```

При **первом создании заведомо нового диска** ожидается ошибка bootstrap
`Cannot identify cookie-data`. Проверьте `/var/log/cloud-init-output.log`: иной
сбой нужно устранить отдельно. Сверьте созданный `production_data_disk_id` и
привязку `device_name = cookie-data` в reviewed plan и Compute Cloud. На VM
проверьте устройство и отсутствие файловой системы:

```bash
sudo lsblk -o NAME,SIZE,TYPE,FSTYPE,UUID,MOUNTPOINTS,SERIAL
sudo wipefs --no-act /dev/disk/by-id/virtio-cookie-data
```

Отсутствие сигнатуры само по себе не доказывает, что диск новый. Только после
подтверждения, что это только что созданный диск без данных, явно инициализируйте
его и повторите bootstrap:

```bash
sudo mkfs.ext4 -L cookie-data /dev/disk/by-id/virtio-cookie-data
sudo /usr/local/sbin/cookie-bootstrap
```

При **новой VM со старым диском**, восстановлении snapshot или любой
неопределённости происхождения диска команду `mkfs.ext4` выполнять нельзя.
Bootstrap должен распознать существующую ext4; если он остановился, сначала
восстановите доступность или файловую систему диска, затем повторите
`sudo /usr/local/sbin/cookie-bootstrap`. Повторное форматирование не является
способом устранить ошибку проверки.

После ручного успешного запуска `cookie-bootstrap` первоначальная ошибка
остаётся в статусе cloud-init. Проверяйте exit code самого скрипта и состояние
установленных сервисов; не очищайте cloud-init state ради повторного запуска:

```bash
docker version
docker buildx version
docker compose version
findmnt /srv/cookie
```

После установки production Compose его внешние параметры должны совпасть с
Terraform outputs:

```bash
terraform output -raw identity_api_gateway_url
terraform output -raw identity_issuer_url
terraform output -raw identity_auth_base_url
```

Проверяйте через gateway только публичные `/v1/auth/*` операции. Отсутствие
gateway-маршрутов для `/healthz` и `/readyz` является намеренной границей, а не
ошибкой конфигурации.

До публичного трафика обязателен live smoke-test client IP: несколько запросов с
разными поддельными `X-Forwarded-For` от одного клиента должны создать один
`*:ip:*` rate-limit scope, а запросы с двух реальных внешних адресов — два. HTTP
integration документирует удаление исходного заголовка, но не обещает формат
добавленного gateway client IP. Если второй тест не проходит, оставьте
`COOKIE_IDENTITY_TRUSTED_PROXY_CIDRS` пустым и перенесите IP-limit на API Gateway
до выяснения фактического поведения платформы.

Новый образ внутри `ubuntu-2604-lts` намеренно не пересоздаёт работающую VM:
обновление boot disk выполняется как отдельная maintenance-операция. Изменение
SSH-ключа в Terraform metadata также следует сопровождать явной ротацией ключа
на хосте или контролируемым rebuild VM.
