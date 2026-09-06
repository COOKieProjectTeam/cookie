output "container_registry_id" {
  description = "Yandex Container Registry ID used in cr.yandex/<registry-id>/<service>."
  value       = yandex_container_registry.production.id
}

output "container_registry_prefix" {
  description = "OCI image prefix for all independently built services."
  value       = "cr.yandex/${yandex_container_registry.production.id}"
}

output "identity_container_repository_id" {
  description = "Repository ID whose IAM policy is scoped to the Identity image only."
  value       = yandex_container_repository.identity.id
}

output "identity_container_repository_name" {
  description = "Repository name used to publish the Identity image."
  value       = yandex_container_repository.identity.name
}

output "ci_registry_pusher_service_account_id" {
  description = "Service account assumed by GitHub Actions through OIDC; its image-management role is scoped to the Identity repository."
  value       = yandex_iam_service_account.ci_registry_pusher.id
}

output "vm_registry_puller_service_account_id" {
  description = "Service account attached to the VM; it can pull only from the Identity repository."
  value       = yandex_iam_service_account.vm_registry_puller.id
}

output "github_oidc_federation_id" {
  description = "Federation ID used by the GitHub Actions token exchange."
  value       = yandex_iam_workload_identity_oidc_federation.github_actions.id
}

output "github_oidc_audience" {
  description = "Audience that the GitHub workflow must request for its OIDC token."
  value       = "https://github.com/${local.github_owner}"
}

output "github_oidc_subject" {
  description = "Only this exact GitHub main-branch subject may assume the CI pusher identity."
  value       = var.github_oidc_subject
}

output "production_vm_public_ip" {
  description = "Static public IPv4 address. Via this address, only allowlisted SSH is reachable."
  value       = yandex_vpc_address.production_vm.external_ipv4_address[0].address
}

output "production_vm_private_ip" {
  description = "Private subnet address of the production VM."
  value       = yandex_compute_instance.production.network_interface[0].ip_address
}

output "production_vm_ssh_user" {
  description = "Operator username created by cloud-init."
  value       = var.admin_username
}

output "production_data_disk_id" {
  description = "Protected persistent disk mounted at /srv/cookie."
  value       = yandex_compute_disk.production_data.id
}

output "identity_api_gateway_id" {
  description = "Temporary public Identity API Gateway ID."
  value       = yandex_api_gateway.identity.id
}

output "identity_api_gateway_url" {
  description = "HTTPS base URL of the temporary Yandex-provided domain; only /v1/auth/* is routed."
  value       = "https://${yandex_api_gateway.identity.domain}"
}

output "identity_issuer_url" {
  description = "COOKIE_IDENTITY_ISSUER value for tokens issued through the temporary gateway."
  value       = "https://${yandex_api_gateway.identity.domain}"
}

output "identity_auth_base_url" {
  description = "Public base URL for Identity contract operations."
  value       = "https://${yandex_api_gateway.identity.domain}/v1/auth"
}

output "ssh_tunnel_command" {
  description = "Emergency operator tunnel to Identity; direct public TCP/8080 ingress remains blocked."
  value       = "ssh -L 8080:127.0.0.1:8080 ${var.admin_username}@${yandex_vpc_address.production_vm.external_ipv4_address[0].address}"
}
