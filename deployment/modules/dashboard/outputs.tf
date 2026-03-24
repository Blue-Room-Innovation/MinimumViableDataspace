output "dashboard_url" {
  value = "http://127.0.0.1${var.base_path}/"
}

output "dashboard_service_name" {
  value = kubernetes_service.dashboard.metadata[0].name
}
