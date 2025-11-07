#!/usr/bin/env bash
set -euo pipefail

# ====================================================
# 🚀 Eclipse MVD Deployment Script (Kubernetes)
# Follows README: 5.1 build -> 5.2 cluster -> 5.3 seed
# ====================================================

CLUSTER_NAME="mvd"
WORK_DIR="$(pwd)"
KIND_CONFIG="./deployment/kind.config.yaml"     # README step 5.2
TERRAFORM_DIR="./deployment"                    # README step 5.2 (cd deployment)
SEED_SCRIPT="./seed-k8s.sh"                     # README step 5.3
NAMESPACE="mvd"

echo "===================================================="
echo "🚀 Eclipse MVD Deployment Script"
echo "===================================================="
echo "🧩 Cluster name: ${CLUSTER_NAME}"
echo "📂 Working dir:  ${WORK_DIR}"
echo "🗂️  Kind config: ${KIND_CONFIG}"
echo "📦 Terraform dir:${TERRAFORM_DIR}"
echo

# ---------- 0) WSL hints (rendimiento/locks) ----------
if [[ "$WORK_DIR" == /mnt/c/* ]]; then
  echo "⚠️ Estás trabajando en /mnt/c (Windows FS). Puede ser lento y causar locks de Gradle/Docker."
  echo "   Recomendado: mover el repo a /home/<user>/... o exportar GRADLE_USER_HOME=~/.gradle"
  export GRADLE_USER_HOME="${HOME}/.gradle"
  echo "ℹ️  Usando GRADLE_USER_HOME=${GRADLE_USER_HOME}"
  echo
fi

# ---------- 1) Dependencias ----------
need() { command -v "$1" >/dev/null 2>&1 || { echo "❌ Falta '$1'"; exit 1; }; }

echo "🔍 Comprobando dependencias..."
for cmd in docker kind kubectl terraform java git; do need "$cmd"; done
# Para seed
for cmd in node npm newman; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "❌ Falta '$cmd' (requerido para el seeding). Instálalo y vuelve a ejecutar."
    exit 1
  fi
done
docker info >/dev/null 2>&1 || { echo "❌ Docker no está arrancado"; exit 1; }
JAVA_LINE=$(java -version 2>&1 | head -n1)
JAVA_MAJOR=$(java -version 2>&1 | awk -F[\"_] '/version/ {print $2}' | cut -d. -f1)
[ "${JAVA_MAJOR}" -ge 17 ] || { echo "❌ Requiere Java >= 17. Detectado: ${JAVA_LINE}"; exit 1; }
echo "☕ ${JAVA_LINE}"
echo "✅ Dependencias OK."
echo

# ---------- 2) Build + Dockerize (README 5.1) ----------
echo "🏗️  Paso de compilación (Gradle + Dockerize)"
echo "----------------------------------------------"
echo "Este paso recompila el código y reconstruye las imágenes Docker locales."
echo "👉 Ejecútalo si:"
echo "   - Has modificado código fuente o dependencias (build.gradle, src/...)."
echo "   - Has cambiado Dockerfiles o configuración."
echo "   - Es la primera vez que ejecutas este script."
echo "⚠️  El README indica: ¡usar SIEMPRE -Ppersistence=true! (Postgres y Vault dependen de ello)."
echo
read -p "¿Quieres realizar el build ahora? (y/N): " build_choice
build_choice=${build_choice:-N}

if [[ "$build_choice" =~ ^[Yy]$ ]]; then
  echo "🚧 Compilando (./gradlew build) y dockerizando (-Ppersistence=true dockerize)..."
  if [ -f "./gradlew" ]; then
    ./gradlew build
    ./gradlew -Ppersistence=true dockerize
  else
    gradle build
    gradle -Ppersistence=true dockerize
  fi
  echo "✅ Build y dockerize completados."
else
  echo "⚡ Saltando el build — se usarán imágenes locales existentes."
  echo "   Si no existen, el despliegue fallará. Ejecuta el build si es tu primera vez."
fi
echo

# ---------- 3) Crear clúster Kind y cargar imágenes (README 5.2) ----------
if kind get clusters | grep -q "^${CLUSTER_NAME}$"; then
  echo "🔁 El clúster '${CLUSTER_NAME}' ya existe. Omitiendo creación."
else
  echo "🧩 Creando clúster Kind '${CLUSTER_NAME}' con ${KIND_CONFIG}..."
  [ -f "${KIND_CONFIG}" ] || { echo "❌ No se encontró ${KIND_CONFIG}"; exit 1; }
  kind create cluster -n "${CLUSTER_NAME}" --config "${KIND_CONFIG}"
  echo "✅ Clúster Kind creado."
fi
echo

# Cargar imágenes locales en Kind (README 5.2)
echo "🐳 Cargando imágenes locales en Kind..."
LOAD_LIST=(controlplane:latest dataplane:latest identity-hub:latest catalog-server:latest issuerservice:latest)
for img in "${LOAD_LIST[@]}"; do
  if docker image inspect "$img" >/dev/null 2>&1; then
    echo "   ▶ kind load docker-image $img -n ${CLUSTER_NAME}"
    kind load docker-image "$img" -n "${CLUSTER_NAME}"
  else
    echo "   ℹ️  Imagen no encontrada localmente: $img (se omitirá)"
  fi
done
echo "✅ Carga de imágenes completada."
echo

# ---------- 4) Ingress NGINX (README 5.2) ----------
if ! kubectl get ns ingress-nginx >/dev/null 2>&1; then
  echo "🌐 Instalando Ingress NGINX para Kind..."
  kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
else
  echo "ℹ️  Namespace 'ingress-nginx' ya existe. Verificando controlador..."
fi

echo "⏳ Esperando Ingress Controller (timeout 90s)..."
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=90s
echo "✅ Ingress listo."
echo

# ---------- 5) Terraform: init & apply (README 5.2) ----------
echo "🌍 Desplegando con Terraform..."
[ -d "${TERRAFORM_DIR}" ] || { echo "❌ No existe ${TERRAFORM_DIR}"; exit 1; }
pushd "${TERRAFORM_DIR}" >/dev/null
terraform init -input=false
terraform apply -auto-approve
popd >/dev/null
echo "✅ Terraform aplicado."
echo

# ---------- 6) Verificar pods (README ejemplo) ----------
echo "🔎 Comprobando pods en namespace '${NAMESPACE}'..."
kubectl get pods -n "${NAMESPACE}" || true
echo

# Espera básica a que al menos los principales estén Running
echo "⏳ Esperando a que los pods clave estén Running..."
KEY_PODS=(consumer-postgres provider-postgres issuer-postgres consumer-vault provider-vault dataspace-issuer-server)
ATTEMPTS=60
SLEEP=5
for name in "${KEY_PODS[@]}"; do
  ok=false
  for i in $(seq 1 ${ATTEMPTS}); do
    if kubectl get pods -n "${NAMESPACE}" | grep -E "${name}" | awk '{print $3}' | grep -qE 'Running|Completed'; then
      echo "✅ ${name} está operativo."
      ok=true; break
    fi
    echo "   ⏱️  Esperando ${name}... (intento $i/${ATTEMPTS})"
    sleep "${SLEEP}"
  done
  if ! $ok; then
    echo "⚠️  ${name} no alcanzó Running a tiempo. Revisa 'kubectl get pods -n ${NAMESPACE}' y logs si es necesario."
  fi
done
echo

# ---------- 7) Seed (README 5.3) ----------
if [ -f "${SEED_SCRIPT}" ]; then
  echo "🌱 Ejecutando seeding (./seed-k8s.sh)..."
  bash "${SEED_SCRIPT}" || echo "⚠️ Seed finalizado con avisos. Verifica los resultados."
else
  echo "⚠️ No se encontró ${SEED_SCRIPT}. El dataspace quedará sin inicializar."
  echo "   Ejecuta luego manualmente: ./seed-k8s.sh"
fi
echo

# ---------- 8) Resumen ----------
echo "===================================================="
echo "✨ Despliegue MVD completado."
echo "----------------------------------------------------"
echo "📦 Imágenes cargadas: ${LOAD_LIST[*]}"
echo "🌐 Ingress listo:     ingress-nginx controller = Ready"
echo "🧩 Terraform dir:     ${TERRAFORM_DIR}"
echo "🌱 Seed script:       ${SEED_SCRIPT}"
echo "----------------------------------------------------"
echo "🔎 Pods (mvd):        kubectl get pods -n ${NAMESPACE}"
echo "🧪 Probar APIs:       http://127.0.0.1/<provider|consumer|issuer>/..."
echo "💡 Si algo falla:     kubectl describe pod <pod> -n ${NAMESPACE}; kubectl logs <pod> -n ${NAMESPACE}"
echo "===================================================="
