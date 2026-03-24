locals {
  connector_config = [
    {
      connectorName            = "Consumer"
      managementUrl            = "http://127.0.0.1/consumer/cp/api/management"
      defaultUrl               = "http://127.0.0.1/consumer/health/api"
      protocolUrl              = "http://127.0.0.1/consumer/cp/api/dsp"
      apiToken                 = var.api_token
      federatedCatalogEnabled  = true
      federatedCatalogUrl      = "http://127.0.0.1/consumer/fc/api/catalog"
      did                      = var.consumer_did
    },
    {
      connectorName            = "Provider QnA"
      managementUrl            = "http://127.0.0.1/provider-qna/cp/api/management"
      defaultUrl               = "http://127.0.0.1/provider-qna/health/api"
      protocolUrl              = "http://127.0.0.1/provider-qna/cp/api/dsp"
      apiToken                 = var.api_token
      federatedCatalogEnabled  = true
      federatedCatalogUrl      = "http://127.0.0.1/provider-qna/fc/api/catalog"
      did                      = var.provider_did
    },
    {
      connectorName            = "Provider Manufacturing"
      managementUrl            = "http://127.0.0.1/provider-manufacturing/cp/api/management"
      defaultUrl               = "http://127.0.0.1/provider-manufacturing/health/api"
      protocolUrl              = "http://127.0.0.1/provider-manufacturing/cp/api/dsp"
      apiToken                 = var.api_token
      federatedCatalogEnabled  = true
      federatedCatalogUrl      = "http://127.0.0.1/provider-manufacturing/fc/api/catalog"
      did                      = var.provider_did
    }
  ]

  app_config = {
    appTitle                   = "MVD Data Dashboard"
    healthCheckIntervalSeconds = 30
    enableUserConfig           = false
    menuItems = [
      {
        text           = "Home"
        materialSymbol = "home_app_logo"
        routerPath     = "home"
        divider        = true
      },
      {
        text           = "Catalog"
        materialSymbol = "book_ribbon"
        routerPath     = "catalog"
      },
      {
        text           = "Assets"
        materialSymbol = "deployed_code_update"
        routerPath     = "assets"
      },
      {
        text           = "Policy Definitions"
        materialSymbol = "policy"
        routerPath     = "policies"
      },
      {
        text           = "Contract Definitions"
        materialSymbol = "contract_edit"
        routerPath     = "contract-definitions"
        divider        = true
      },
      {
        text           = "Contracts"
        materialSymbol = "handshake"
        routerPath     = "contracts"
      },
      {
        text           = "Transfer History"
        materialSymbol = "schedule_send"
        routerPath     = "transfer-history"
      }
    ]
  }
}

resource "kubernetes_config_map" "dashboard_config" {
  metadata {
    name      = "${var.name}-config"
    namespace = var.namespace
  }

  data = {
    "edc-connector-config.json" = jsonencode(local.connector_config)
    "app-config.json"           = jsonencode(local.app_config)
    "APP_BASE_HREF.txt"         = "${var.base_path}/"
  }
}

resource "kubernetes_deployment" "dashboard" {
  metadata {
    name      = var.name
    namespace = var.namespace
    labels = {
      App = var.name
    }
  }

  spec {
    replicas = 1

    selector {
      match_labels = {
        App = var.name
      }
    }

    template {
      metadata {
        labels = {
          App = var.name
        }
      }

      spec {
        container {
          name              = var.name
          image             = var.image
          image_pull_policy = var.image_pull_policy

          port {
            container_port = var.service_port
            name           = "http"
          }

          liveness_probe {
            http_get {
              path = "/"
              port = var.service_port
            }
            failure_threshold = 10
            period_seconds    = 10
            timeout_seconds   = 5
          }

          readiness_probe {
            http_get {
              path = "/"
              port = var.service_port
            }
            failure_threshold = 10
            period_seconds    = 10
            timeout_seconds   = 5
          }

          volume_mount {
            name       = "dashboard-config"
            mount_path = "/app/config"
            read_only  = true
          }
        }

        volume {
          name = "dashboard-config"
          config_map {
            name = kubernetes_config_map.dashboard_config.metadata[0].name
          }
        }
      }
    }
  }
}

resource "kubernetes_service" "dashboard" {
  metadata {
    name      = var.name
    namespace = var.namespace
  }

  spec {
    selector = {
      App = kubernetes_deployment.dashboard.spec[0].template[0].metadata[0].labels.App
    }

    port {
      name = "http"
      port = var.service_port
    }
  }
}

resource "kubernetes_ingress_v1" "dashboard" {
  metadata {
    name      = "${var.name}-ingress"
    namespace = var.namespace
    annotations = {
      "nginx.ingress.kubernetes.io/rewrite-target" = "/$2"
      "nginx.ingress.kubernetes.io/use-regex"      = "true"
    }
  }

  spec {
    ingress_class_name = "nginx"

    rule {
      http {
        path {
          path = "${var.base_path}(/|$)(.*)"
          path_type = "ImplementationSpecific"

          backend {
            service {
              name = kubernetes_service.dashboard.metadata[0].name
              port {
                number = var.service_port
              }
            }
          }
        }
      }
    }
  }
}
