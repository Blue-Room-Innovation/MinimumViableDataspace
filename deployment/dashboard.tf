module "dashboard" {
  source      = "./modules/dashboard"
  namespace   = kubernetes_namespace.ns.metadata.0.name
  consumer_did = var.consumer-did
  provider_did = var.provider-did
}
