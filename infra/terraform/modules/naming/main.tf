variable "application" {
  type = string
}

variable "environment" {
  type = string
}

locals {
  prefix = "${var.application}-${var.environment}"
}

output "prefix" {
  value = local.prefix
}
