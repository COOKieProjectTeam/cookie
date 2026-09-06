variable "cloud_id" {
  description = "Yandex Cloud ID containing the production folder."
  type        = string
  nullable    = false

  validation {
    condition     = length(trimspace(var.cloud_id)) > 0
    error_message = "cloud_id must not be empty."
  }
}

variable "folder_id" {
  description = "Dedicated Yandex Cloud folder ID for production resources."
  type        = string
  nullable    = false

  validation {
    condition     = length(trimspace(var.folder_id)) > 0
    error_message = "folder_id must not be empty."
  }
}

variable "zone" {
  description = "Availability zone for the single production VM and its disks."
  type        = string
  default     = "ru-central1-d"
  nullable    = false

  validation {
    condition     = contains(["ru-central1-a", "ru-central1-b", "ru-central1-d", "ru-central1-e"], var.zone)
    error_message = "zone must be one of the current regular Yandex Cloud zones: ru-central1-a, -b, -d, or -e."
  }
}

variable "name_prefix" {
  description = "Lowercase prefix applied to production resources."
  type        = string
  default     = "cookie-production"
  nullable    = false

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,38}[a-z0-9]$", var.name_prefix))
    error_message = "name_prefix must be 3-40 lowercase characters, digits, or hyphens and must not end with a hyphen."
  }
}

variable "subnet_cidrs" {
  description = "Private, non-overlapping IPv4 CIDRs for every regular availability zone required by API Gateway VPC connectivity."
  type        = map(string)
  default = {
    ru-central1-a = "10.42.0.0/24"
    ru-central1-b = "10.42.1.0/24"
    ru-central1-d = "10.42.2.0/24"
    ru-central1-e = "10.42.3.0/24"
  }
  nullable = false

  validation {
    condition = (
      toset(keys(var.subnet_cidrs)) == toset([
        "ru-central1-a",
        "ru-central1-b",
        "ru-central1-d",
        "ru-central1-e",
      ]) &&
      alltrue([
        for cidr in values(var.subnet_cidrs) : (
          can(cidrnetmask(cidr)) &&
          try(tonumber(split("/", cidr)[1]) >= 16, false) &&
          try(tonumber(split("/", cidr)[1]) <= 28, false)
        )
      ]) &&
      length(distinct(values(var.subnet_cidrs))) == 4
    )
    error_message = "subnet_cidrs must have distinct valid /16 through /28 IPv4 CIDRs for exactly ru-central1-a, -b, -d, and -e."
  }
}

variable "ssh_allowed_cidrs" {
  description = "Trusted IPv4 source CIDRs allowed to connect to SSH. Use operator or VPN /32 addresses; 0.0.0.0/0 is rejected."
  type        = list(string)
  nullable    = false

  validation {
    condition = (
      length(var.ssh_allowed_cidrs) > 0 &&
      alltrue([for cidr in var.ssh_allowed_cidrs : can(cidrnetmask(cidr))])
    )
    error_message = "ssh_allowed_cidrs must contain at least one valid IPv4 CIDR."
  }

  validation {
    condition = alltrue([
      for cidr in var.ssh_allowed_cidrs : try(tonumber(split("/", cidr)[1]) >= 24, false)
    ])
    error_message = "Every SSH source must be /24 or narrower; prefer one operator or VPN address as /32."
  }
}

variable "ssh_public_key" {
  description = "Public SSH key installed for the production operator. Never provide a private key."
  type        = string
  nullable    = false

  validation {
    condition = can(regex(
      "^(ssh-ed25519|ecdsa-sha2-nistp256|ecdsa-sha2-nistp384|ecdsa-sha2-nistp521|ssh-rsa) [A-Za-z0-9+/=]+( .*)?$",
      trimspace(var.ssh_public_key),
    ))
    error_message = "ssh_public_key must be a single OpenSSH public key."
  }
}

variable "admin_username" {
  description = "Unprivileged VM operator account created by cloud-init."
  type        = string
  default     = "cookieops"
  nullable    = false

  validation {
    condition = (
      can(regex("^[a-z_][a-z0-9_-]{0,30}$", var.admin_username)) &&
      var.admin_username != "root"
    )
    error_message = "admin_username must be a valid lowercase Linux username other than root."
  }
}

variable "github_repository" {
  description = "GitHub repository in owner/name form; used to derive the OIDC audience and resource description."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must use the owner/name form."
  }
}

variable "github_oidc_subject" {
  description = "Exact GitHub OIDC sub claim allowed to assume the CI pusher identity. It must identify this repository's main ref; prefer GitHub's immutable owner/repository-ID form when available."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^repo:[^[:space:]:]+:ref:refs/heads/main$", var.github_oidc_subject))
    error_message = "github_oidc_subject must be an exact repo subject ending in :ref:refs/heads/main."
  }
}

variable "vm_platform_id" {
  description = "Yandex Compute Cloud platform. standard-v3 supports the low baseline CPU fraction used here."
  type        = string
  default     = "standard-v3"
  nullable    = false

  validation {
    condition     = length(trimspace(var.vm_platform_id)) > 0
    error_message = "vm_platform_id must not be empty."
  }
}

variable "vm_cores" {
  description = "VM vCPU count. The MVP default is the smallest practical size for the JVM service, PostgreSQL, and NATS."
  type        = number
  default     = 2
  nullable    = false

  validation {
    condition     = var.vm_cores >= 2 && floor(var.vm_cores) == var.vm_cores
    error_message = "vm_cores must be an integer of at least 2."
  }
}

variable "vm_memory_gib" {
  description = "VM RAM in GiB. Four GiB is the initial non-HA production floor."
  type        = number
  default     = 4
  nullable    = false

  validation {
    condition     = var.vm_memory_gib >= 4
    error_message = "vm_memory_gib must be at least 4 GiB."
  }
}

variable "vm_core_fraction" {
  description = "Guaranteed baseline CPU percentage. The practical production default is 50; 20 is an explicit cost-saving override."
  type        = number
  default     = 50
  nullable    = false

  validation {
    condition     = contains([20, 50, 100], var.vm_core_fraction)
    error_message = "vm_core_fraction must be one of 20, 50, or 100."
  }
}

variable "boot_disk_size_gib" {
  description = "Boot disk size in GiB. Application data must not be stored here."
  type        = number
  default     = 30
  nullable    = false

  validation {
    condition     = var.boot_disk_size_gib >= 20 && floor(var.boot_disk_size_gib) == var.boot_disk_size_gib
    error_message = "boot_disk_size_gib must be an integer of at least 20 GiB."
  }
}

variable "data_disk_size_gib" {
  description = "Persistent data disk size in GiB, mounted at /srv/cookie."
  type        = number
  default     = 30
  nullable    = false

  validation {
    condition     = var.data_disk_size_gib >= 20 && floor(var.data_disk_size_gib) == var.data_disk_size_gib
    error_message = "data_disk_size_gib must be an integer of at least 20 GiB."
  }
}

variable "ubuntu_image_family" {
  description = "Yandex Marketplace image family. Ubuntu 26.04 LTS is supported by the official Docker apt repository."
  type        = string
  default     = "ubuntu-2604-lts"
  nullable    = false

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,61}[a-z0-9]$", var.ubuntu_image_family))
    error_message = "ubuntu_image_family must be a valid Yandex Cloud image family name."
  }
}
