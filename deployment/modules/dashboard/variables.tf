variable "namespace" {
  type = string
}

variable "name" {
  type        = string
  description = "Kubernetes resource name for the dashboard"
  default     = "mvd-dashboard"
}

variable "image" {
  type        = string
  description = "Docker image used by the dashboard deployment"
  default     = "dashboard:latest"
}

variable "image_pull_policy" {
  type        = string
  description = "Kubernetes imagePullPolicy"
  default     = "Never"
}

variable "service_port" {
  type        = number
  description = "Dashboard service port"
  default     = 8080
}

variable "base_path" {
  type        = string
  description = "Ingress path where dashboard is exposed"
  default     = "/dashboard"
}

variable "api_token" {
  type        = string
  description = "Default API token used by the DataDashboard connector config"
  default     = "password"
}

variable "consumer_did" {
  type        = string
  description = "DID for the consumer connector"
}

variable "provider_did" {
  type        = string
  description = "DID for the provider connector"
}
