#!/usr/bin/env bash
set -euo pipefail

# =====================================================
#  Eclipse Minimum Viable Dataspace (MVD) Cleanup Script
#  Safe full cleanup of Kind, Terraform, and Docker artifacts
# =====================================================

CLUSTER_NAME="mvd"
DEPLOY_DIR="./deployment/terraform"
CONFIRM=${1:-""}

echo "===================================================="
echo "🧹 Eclipse MVD Cleanup Utility"
echo "===================================================="
echo "🧩 Target cluster: $CLUSTER_NAME"
echo "📂 Terraform dir: $DEPLOY_DIR"
echo

# -----------------------------------------------------
# 1️⃣ Confirmation prompt
# -----------------------------------------------------
if [[ "$CONFIRM" != "--yes" && "$CONFIRM" != "-y" ]]; then
  read -r -p "⚠️  This will permanently delete the MVD cluster, Terraform state, and Docker containers. Continue? (y/N): " confirm
  if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "❌ Aborted by user."
    exit 0
  fi
fi

# -----------------------------------------------------
# 2️⃣ Stop and remove Docker containers related to MVD
# -----------------------------------------------------
echo "🐳 Searching for Docker containers related to '$CLUSTER_NAME'..."

# Guardamos la salida en una variable para evitar pipes bloqueantes
CONTAINERS=$(docker ps -a --filter "name=${CLUSTER_NAME}" --format "{{.ID}}" || true)

if [[ -z "${CONTAINERS}" ]]; then
  echo "ℹ️ No Docker containers found matching '$CLUSTER_NAME'."
else
  echo "🧱 Found containers:"
  docker ps -a --filter "name=${CLUSTER_NAME}" --format "  - {{.Names}} ({{.ID}})"
  echo
  for id in ${CONTAINERS}; do
    echo "🧨 Removing container ${id}..."
    docker rm -f "${id}" >/dev/null 2>&1 || true
  done
  echo "✅ Containers removed."
fi
echo

# -----------------------------------------------------
# 3️⃣ Delete Kind cluster if exists
# -----------------------------------------------------
if kind get clusters | grep -q "^${CLUSTER_NAME}$"; then
  echo "🧩 Deleting Kind cluster '${CLUSTER_NAME}'..."
  kind delete cluster --name "${CLUSTER_NAME}"
  echo "✅ Cluster deleted."
else
  echo "ℹ️ No existing Kind cluster named '${CLUSTER_NAME}' found."
fi
echo

# -----------------------------------------------------
# 4️⃣ Cleanup Terraform state and cache
# -----------------------------------------------------
if [ -d "${DEPLOY_DIR}" ]; then
  echo "🗑️  Cleaning Terraform state in ${DEPLOY_DIR}..."
  find "${DEPLOY_DIR}" -maxdepth 1 -type f \( \
    -name "terraform.tfstate*" -o \
    -name ".terraform.lock.hcl" \
  \) -exec rm -f {} \; || true
  rm -rf "${DEPLOY_DIR}/.terraform" || true
  echo "✅ Terraform state cleaned."
else
  echo "ℹ️ Terraform directory not found. Skipping."
fi
echo

# -----------------------------------------------------
# 5️⃣ Remove unused Docker resources (optional)
# -----------------------------------------------------
echo "🧼 Pruning unused Docker images, volumes, and networks..."
docker system prune -a -f --volumes >/dev/null 2>&1 || true
echo "✅ Docker system cleaned."
echo

# -----------------------------------------------------
# 6️⃣ Final summary
# -----------------------------------------------------
echo "✨ Environment cleanup completed!"
echo "--------------------------------------"
echo "🧩 Cluster '${CLUSTER_NAME}': removed"
echo "📦 Terraform state: deleted"
echo "🐳 Docker system: pruned"
echo "--------------------------------------"
echo "✅ Your system is now clean and ready for a fresh deployment."
