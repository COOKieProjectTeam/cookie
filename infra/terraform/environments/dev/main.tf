terraform {
  required_version = ">= 1.5.0"
}

module "naming" {
  source = "../../modules/naming"

  application = "cookie"
  environment = "dev"
}

output "resource_prefix" {
  value = module.naming.prefix
}
