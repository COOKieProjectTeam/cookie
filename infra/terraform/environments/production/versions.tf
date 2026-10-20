terraform {
  required_version = "~> 1.16.0"

  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "= 0.223.0"
    }
  }

  backend "s3" {
    endpoints = {
      s3 = "https://storage.yandexcloud.net"
    }

    bucket = "replace-with-production-state-bucket"

    key          = "cookie/production/terraform.tfstate"
    region       = "ru-central1"
    use_lockfile = true

    skip_region_validation      = true
    skip_credentials_validation = true
    skip_requesting_account_id  = true
    skip_s3_checksum            = true
  }
}
