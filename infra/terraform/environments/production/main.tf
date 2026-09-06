locals {
  labels = {
    application = "cookie"
    environment = "production"
    managed-by  = "terraform"
  }

  github_owner            = split("/", var.github_repository)[0]
  api_gateway_source_cidr = "198.19.0.0/16"
}

data "yandex_compute_image" "ubuntu" {
  family = var.ubuntu_image_family
}

resource "yandex_vpc_network" "production" {
  folder_id   = var.folder_id
  name        = "${var.name_prefix}-network"
  description = "COOKie production network"
  labels      = local.labels
}

resource "yandex_vpc_subnet" "production" {
  for_each = var.subnet_cidrs

  folder_id      = var.folder_id
  name           = "${var.name_prefix}-subnet-${replace(each.key, "ru-central1-", "")}"
  description    = "COOKie production subnet in ${each.key}; required by API Gateway connectivity"
  zone           = each.key
  network_id     = yandex_vpc_network.production.id
  v4_cidr_blocks = [each.value]
  labels         = local.labels
}

resource "yandex_vpc_security_group" "production_vm" {
  folder_id   = var.folder_id
  name        = "${var.name_prefix}-vm-sg"
  description = "SSH allowlist plus Identity ingress from API Gateway only"
  network_id  = yandex_vpc_network.production.id
  labels      = local.labels

  ingress {
    description    = "Operator SSH from explicitly trusted addresses"
    protocol       = "TCP"
    port           = 22
    v4_cidr_blocks = var.ssh_allowed_cidrs
  }

  ingress {
    description    = "Identity HTTP from API Gateway VPC integration only"
    protocol       = "TCP"
    port           = 8080
    v4_cidr_blocks = [local.api_gateway_source_cidr]
  }

  egress {
    description    = "Outbound updates, Yandex APIs, and Container Registry pulls"
    protocol       = "ANY"
    v4_cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "yandex_vpc_address" "production_vm" {
  folder_id           = var.folder_id
  name                = "${var.name_prefix}-vm-ip"
  description         = "Stable SSH address for the initial production VM"
  deletion_protection = true
  labels              = local.labels

  external_ipv4_address {
    zone_id = var.zone
  }
}

resource "yandex_container_registry" "production" {
  folder_id = var.folder_id
  name      = "${var.name_prefix}-registry"
  labels    = local.labels
}

resource "yandex_container_repository" "identity" {
  name = "${yandex_container_registry.production.id}/identity"
}

resource "yandex_iam_service_account" "ci_registry_pusher" {
  folder_id   = var.folder_id
  name        = "${var.name_prefix}-ci-pusher"
  description = "GitHub Actions image publisher scoped to the Identity repository"
}

resource "yandex_iam_service_account" "vm_registry_puller" {
  folder_id   = var.folder_id
  name        = "${var.name_prefix}-vm-puller"
  description = "Production VM image puller scoped to the Identity repository"
}

resource "yandex_container_repository_iam_binding" "ci_identity_pusher" {
  repository_id = yandex_container_repository.identity.id
  role          = "container-registry.images.pusher"

  members = [
    "serviceAccount:${yandex_iam_service_account.ci_registry_pusher.id}",
  ]
}

resource "yandex_container_repository_iam_binding" "vm_identity_puller" {
  repository_id = yandex_container_repository.identity.id
  role          = "container-registry.images.puller"

  members = [
    "serviceAccount:${yandex_iam_service_account.vm_registry_puller.id}",
  ]
}

resource "yandex_iam_workload_identity_oidc_federation" "github_actions" {
  folder_id   = var.folder_id
  name        = "${var.name_prefix}-github"
  description = "GitHub Actions OIDC federation for ${var.github_repository}"
  disabled    = false
  issuer      = "https://token.actions.githubusercontent.com"
  audiences   = ["https://github.com/${local.github_owner}"]
  jwks_url    = "https://token.actions.githubusercontent.com/.well-known/jwks"
  labels      = local.labels
}

resource "yandex_iam_workload_identity_federated_credential" "github_main_ci_pusher" {
  service_account_id  = yandex_iam_service_account.ci_registry_pusher.id
  federation_id       = yandex_iam_workload_identity_oidc_federation.github_actions.id
  external_subject_id = var.github_oidc_subject
}

resource "yandex_compute_disk" "production_data" {
  folder_id   = var.folder_id
  name        = "${var.name_prefix}-data"
  description = "Persistent PostgreSQL and JetStream data for the initial production host"
  type        = "network-hdd"
  zone        = var.zone
  size        = var.data_disk_size_gib
  labels      = local.labels

  # Production data must require an explicit code review before deletion.
  lifecycle {
    prevent_destroy = true
  }
}

resource "yandex_compute_instance" "production" {
  folder_id                 = var.folder_id
  name                      = "${var.name_prefix}-vm"
  hostname                  = "${var.name_prefix}-vm"
  description               = "Initial non-HA COOKie production host"
  zone                      = var.zone
  platform_id               = var.vm_platform_id
  service_account_id        = yandex_iam_service_account.vm_registry_puller.id
  allow_stopping_for_update = true
  labels                    = local.labels

  resources {
    cores         = var.vm_cores
    memory        = var.vm_memory_gib
    core_fraction = var.vm_core_fraction
  }

  scheduling_policy {
    preemptible = false
  }

  boot_disk {
    auto_delete = true

    initialize_params {
      name        = "${var.name_prefix}-boot"
      description = "Replaceable OS disk; production data lives on the attached data disk"
      image_id    = data.yandex_compute_image.ubuntu.id
      type        = "network-hdd"
      size        = var.boot_disk_size_gib
    }
  }

  secondary_disk {
    disk_id     = yandex_compute_disk.production_data.id
    device_name = "cookie-data"
    mode        = "READ_WRITE"
    auto_delete = false
  }

  network_interface {
    subnet_id          = yandex_vpc_subnet.production[var.zone].id
    nat                = true
    nat_ip_address     = yandex_vpc_address.production_vm.external_ipv4_address[0].address
    security_group_ids = [yandex_vpc_security_group.production_vm.id]
  }

  metadata_options {
    # The deploy process can exchange the attached pull-only service account
    # token through the GCE-compatible metadata endpoint.
    gce_http_endpoint    = 1
    gce_http_token       = 1
    aws_v1_http_endpoint = 2
    aws_v1_http_token    = 2
    aws_v2_http_endpoint = 2
    aws_v2_http_token    = 2
  }

  metadata = {
    serial-port-enable = "0"
    user-data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
      admin_username = var.admin_username
      ssh_public_key = jsonencode(trimspace(var.ssh_public_key))
    })
  }

  # A newer family image must not silently replace the running production VM.
  # Rebuild the boot disk explicitly as a reviewed maintenance operation.
  lifecycle {
    ignore_changes = [boot_disk[0].initialize_params[0].image_id]
  }

  depends_on = [
    yandex_container_repository_iam_binding.vm_identity_puller,
  ]
}

resource "yandex_api_gateway" "identity" {
  folder_id         = var.folder_id
  name              = "${var.name_prefix}-identity-gateway"
  description       = "Temporary HTTPS edge for Identity until the COOKie domain and Caddy are ready"
  labels            = local.labels
  execution_timeout = "15"

  connectivity {
    network_id = yandex_vpc_network.production.id
  }

  # Only Identity's public contract is reachable. Operational endpoints,
  # PostgreSQL, and NATS are intentionally absent from this specification.
  spec = yamlencode({
    openapi = "3.0.0"
    info = {
      title   = "COOKie Identity bootstrap gateway"
      version = "1.0.0"
    }
    paths = {
      "/v1/auth/{path+}" = {
        "x-yc-apigateway-any-method" = {
          operationId = "identityProxy"
          parameters = [
            {
              name     = "path"
              in       = "path"
              required = false
              schema = {
                type = "string"
              }
            },
          ]
          responses = {
            default = {
              description = "Response proxied from Identity Service"
            }
          }
          "x-yc-apigateway-integration" = {
            type = "http"
            url  = "http://${yandex_compute_instance.production.network_interface[0].ip_address}:8080/v1/auth/{path}"
            headers = {
              "*"               = "*"
              "X-Forwarded-For" = ""
            }
            omitEmptyHeaders = true
            query = {
              "*" = "*"
            }
            timeouts = {
              connect = 2
              read    = 12
            }
          }
        }
      }
    }
  })

  # API Gateway requires a subnet in every active zone and at least one network
  # resource before its user-network attachment is created.
  depends_on = [
    yandex_vpc_subnet.production,
    yandex_compute_instance.production,
  ]
}
