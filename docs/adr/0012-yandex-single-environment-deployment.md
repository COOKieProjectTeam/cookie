# ADR 0012: Минимальный production-контур в Yandex Cloud

- Status: accepted
- Date: 2026-09-04

## Context

На текущем этапе проекту нужен один доступный backend-контур, но отдельный
тестовый стенд и собственный домен ещё не оправдывают стоимость и операционную
сложность. Production-ready реализация есть только у Identity Service. Остальные
доменные сервисы пока существуют в целевой архитектурной модели, а
`notification-sink`, Mailpit и legacy Go API являются development tooling.

Identity нельзя публиковать по открытому HTTP: он выдаёт bearer tokens и
принимает пароль. Одновременно маленькая production VM не должна собирать
исходный код и хранить CI credentials. Нужны воспроизводимые per-service images,
короткоживущая аутентификация CI и явное разделение local development и cloud
production.

## Decision

До появления необходимости в отдельном test environment используются два
контура:

- `dev` запускается разработчиком локально через Docker Compose и собирает
  Identity и development Notification sink из текущего checkout;
- `production` разворачивается в Yandex Cloud. CI и disposable Testcontainers в
  pull request являются обязательным gate вместо постоянно работающего test
  stand.

Production image создаётся GitHub Actions отдельно для каждого реально
deployable сервиса. Сейчас publish job есть только у `identity`; будущий сервис
получает собственные Dockerfile, job, image name и digest. Образ строится один
раз для `linux/amd64`, отправляется в Yandex Container Registry с полным commit
SHA и разворачивается по immutable digest. Теги `latest` и сборка исходников на
production VM запрещены.

GitHub получает короткоживущий Yandex IAM token через OIDC Workload Identity
Federation. Долгоживущий service-account key в GitHub Secrets не создаётся.
Federated credential разрешает только фактический GitHub `sub` для `main`, а CI
service account получает `container-registry.images.pusher` только на repository
конкретного сервиса. Эта роль включает изменение и удаление образов внутри
repository, поэтому репозитории и IAM-привязки разделяются по сервисам. Service
account VM отдельно имеет pull только из deployable repository и получает IAM
token из metadata service.

Начальный runtime — одна non-preemptible `standard-v3` VM: 2 vCPU, 4 GiB RAM,
50% core fraction и отдельный persistent data disk для PostgreSQL и JetStream.
20% допускается как явный cost-saving override после нагрузочной проверки, но
не является default для JVM, PostgreSQL и NATS на одном узле. Compose запускает
только Identity, PostgreSQL и NATS. Это осознанно не-HA конфигурация и single
point of failure; automatic horizontal scaling и zero-downtime deploy не
заявляются. Bootstrap только проверяет и монтирует существующую ext4; ошибки
определения файловой системы останавливают его. Первое форматирование заведомо
нового диска выполняет оператор отдельной командой. Пересоздание VM со старым
диском никогда не запускает форматирование автоматически.

До собственного домена внешний TLS завершается на default HTTPS domain Yandex
API Gateway. Gateway подключён к VPC, проксирует только public Identity paths
`/v1/auth/*` на private VM и передаёт исходные headers/query parameters. В
частности, нельзя потерять `Authorization` и `Idempotency-Key`; присланный
клиентом `X-Forwarded-For` при этом удаляется и не считается доверенным.
`/healthz` и
`/readyz` остаются непубличными. Security group разрешает application port
только диапазону подсетей API Gateway, а SSH — только явно заданным admin CIDR.
После появления домена временный gateway заменяется целевым Caddy edge с
Certificate Manager certificate; product OpenAPI при этом не меняется.

PostgreSQL и NATS не публикуют host ports. NATS использует TLS, отдельные
credentials для Identity и stream administration и least-privilege subject
permissions. Production Identity не создаёт JetStream stream самостоятельно:
deployment bootstrap создаёт `COOKIE_EVENTS` для `cookie.events.>` до запуска
приложения. Key material и пароли хранятся только в root-owned files на сервере,
монтируются read-only и не попадают в Git, Terraform state или GitHub.
Перенос секретов в Lockbox остаётся отдельным hardening step.

Terraform state хранится отдельно в versioned encrypted Object Storage bucket.
Backend настраивается partial configuration вне Git; shared writes разрешаются
только после проверки lockfile locking на выбранном S3-compatible endpoint.
Ни state, ни реальные identifiers/variables не коммитятся.

Deploy запускается оператором на VM по конкретному image digest. Скрипт получает
короткоживущий registry token из VM metadata, сериализует deployments lock-файлом,
проверяет readiness и при неуспехе возвращает предыдущий image digest. GitHub
runner не получает SSH-доступ к production subnet. Автоматический remote deploy
можно добавить позднее вместе с OS Login, audit trail и отдельным approval gate.

## Consequences

- Постоянная стоимость и поверхность атаки минимальны, но отказ VM, зоны,
  локальных PostgreSQL или NATS делает production недоступным.
- База и JetStream находятся на одном failure domain. Нужны snapshots/backups,
  restore drill и внешние alerts до хранения ценных production-данных.
- Default domain API Gateway является временным адресом. Issuer Identity задаётся
  его HTTPS URL через production configuration и будет осознанно изменён при
  миграции на собственный домен.
- Publish и deploy разделены: зелёный CI создаёт артефакт, но сам по себе не
  меняет production.
- Отсутствие production Notification Service означает, что регистрационные
  письма пока некому доставлять. Development sink нельзя разворачивать как
  замену; это известное функциональное ограничение первого production slice.
- Отдельный staging/test stand добавляется только отдельным решением, когда
  появятся параллельные релизы, migrations с высоким риском или реальные
  интеграции, которые нельзя достаточно проверить в CI.
