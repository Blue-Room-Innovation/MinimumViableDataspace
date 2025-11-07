#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# store-vault-secret.sh
# -----------------------------------------------------------------------------
# Pequeño helper para guardar/actualizar un secreto en el HashiCorp Vault que
# utiliza el despliegue del Minimum Viable Dataspace.
#
# Características:
# - Escribe el valor bajo la clave "content" → exactamente lo que espera
#   `secretName` en un `HttpDataAddress`.
# - Funciona igual para bearer tokens, API keys u otros secretos.
# - Si no proporcionas el valor en una variable, puede leerlo desde un fichero
#   o pedirlo por consola (sin hacer echo).
#
# Requisitos: `curl` y `jq` instalados en PATH.
#
# Variables de entorno (todas opcionales):
#   VAULT_ADDR    → URL del Vault (default http://127.0.0.1:8200).
#                   Recuerda hacer `kubectl port-forward svc/provider-vault -n mvd 8200:8200`
#                   para exponerlo en local.
#   VAULT_TOKEN   → Token con permisos para escribir (default root en MVD).
#   SECRET_NAME   → Nombre lógico del secreto (sin prefijo `secret/`, default secure-api).
#   SECRET_VALUE  → Valor literal que quieres guardar (p.ej. `Bearer eyJ...` o `12345-API`).
#                   Si está vacío, el script revisa `SECRET_FILE`.
#   SECRET_FILE   → Ruta a un fichero con el valor del secreto. Se usa solo si
#                   `SECRET_VALUE` está vacío. Útil para evitar exponer el token
#                   en el historial o en `ps`.
#
# Ejemplos:
#
# 1) Guardar un bearer leyendo la cadena desde variable:
#    kubectl port-forward svc/provider-vault -n mvd 8200:8200 &
#    VAULT_ADDR=http://127.0.0.1:8200 \
#    VAULT_TOKEN=root \
#    SECRET_NAME=secure-api \
#    SECRET_VALUE="Bearer eyJhbGciOi..." \
#    ./store-vault-secret.sh
#
# 2) Guardar una API Key desde un archivo:
#    echo "123456-API-KEY" > /tmp/api.key
#    SECRET_FILE=/tmp/api.key ./store-vault-secret.sh
#
# 3) Sin variables → el script pedirá el valor por consola (oculto):
#    ./store-vault-secret.sh
#
# Nota: cada vez que sobrescribas el secreto no hace falta reiniciar nada; el dataplane
#       tomará la última versión cuando ejecute una transferencia.
# -----------------------------------------------------------------------------

VAULT_ADDR="${VAULT_ADDR:-http://127.0.0.1:8200}"
VAULT_TOKEN="${VAULT_TOKEN:-root}"
SECRET_NAME="${SECRET_NAME:-secure-api}"
SECRET_VALUE="${SECRET_VALUE:-}"
SECRET_FILE="${SECRET_FILE:-}"

log() {
  printf '[store-vault-secret] %s\n' "$*"
}

need() {
  command -v "$1" >/dev/null 2>&1 || { log "Error: '$1' no esta instalado en PATH"; exit 1; }
}

need curl
need jq

if [[ -z "${SECRET_VALUE}" ]]; then
  if [[ -n "${SECRET_FILE}" ]]; then
    if [[ -f "${SECRET_FILE}" ]]; then
      SECRET_VALUE="$(<"${SECRET_FILE}")"
    else
      log "Error: SECRET_FILE='${SECRET_FILE}' no existe"
      exit 1
    fi
  else
    read -rsp "Introduce el valor del secreto: " SECRET_VALUE
    echo
  fi
fi

if [[ -z "${SECRET_VALUE}" ]]; then
  log "Error: SECRET_VALUE vacio"
  exit 1
fi

PAYLOAD=$(jq -n --arg value "$SECRET_VALUE" '{data:{content:$value}}')

log "Escribiendo secreto '${SECRET_NAME}' en ${VAULT_ADDR}"
HTTP_CODE=$(curl -sS -o /dev/null -w "%{http_code}" \
  -X POST "${VAULT_ADDR%/}/v1/secret/data/${SECRET_NAME}" \
  -H "X-Vault-Token: ${VAULT_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "${PAYLOAD}")

if [[ "${HTTP_CODE}" == "200" || "${HTTP_CODE}" == "204" ]]; then
  log "Secreto almacenado correctamente."
else
  log "Fallo al almacenar el secreto (HTTP ${HTTP_CODE}). Revisa VAULT_ADDR/VAULT_TOKEN."
  exit 1
fi
