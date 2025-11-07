#!/usr/bin/env bash
set -euo pipefail

#
# Registers a HttpData asset, its access policy and the matching contract definition
# against a provider controlplane management API.
#
# Defaults can be overridden through environment variables:
#   BASE_URL=http://127.0.0.1/provider-qna/cp
#   API_KEY=provider-api-key
#   ASSET_ID=asset-secure-endpoint
#   ASSET_BASE_URL=https://api.circularpass.io/api/secure/v1/instances
#   SECRET_NAME=secure-api
#   POLICY_ID=require-membership
#   CONTRACT_DEF_ID=secure-asset-membership-required-def-2
#

BASE_URL="${BASE_URL:-http://127.0.0.1/provider-qna/cp}"
API_KEY="${API_KEY:-password}"
ASSET_ID="${ASSET_ID:-asset-secure-endpoint}"
ASSET_BASE_URL="${ASSET_BASE_URL:-https://api.circularpass.io/api/secure/v1/instances}"
SECRET_NAME="${SECRET_NAME:-secure-api}"
POLICY_ID="${POLICY_ID:-require-membership}"
CONTRACT_DEF_ID="${CONTRACT_DEF_ID:-secure-asset-membership-required-def-2}"

MGMT_ROOT="${BASE_URL%/}/api/management/v3"
CURL_COMMON=(-sS -H "Content-Type: application/json" -H "X-Api-Key: ${API_KEY}")

log() {
  printf '[seed-secure-asset] %s\n' "$*"
}

request() {
  local method=$1
  local path=$2
  local body=$3
  curl "${CURL_COMMON[@]}" -X "${method}" "${MGMT_ROOT}${path}" -d "${body}"
}

delete_if_exists() {
  local resource=$1
  local id=$2
  local url="${MGMT_ROOT}/${resource}/${id}"
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Api-Key: ${API_KEY}" -X DELETE "${url}" || true)
  case "${status}" in
    204) log "Removed previous ${resource}/${id}";;
    404|"") log "${resource}/${id} not present (skip delete)";;
    *) log "Warning: delete ${resource}/${id} returned HTTP ${status}";;
  esac
}

log "Seeding against ${MGMT_ROOT}"

delete_if_exists assets "${ASSET_ID}"
delete_if_exists policydefinitions "${POLICY_ID}"
delete_if_exists contractdefinitions "${CONTRACT_DEF_ID}"

ASSET_PAYLOAD=$(cat <<JSON
{
  "@context": [
    "https://w3id.org/edc/connector/management/v0.0.1"
  ],
  "@id": "${ASSET_ID}",
  "@type": "Asset",
  "properties": {
    "edc:name": "Secure API asset",
    "edc:description": "Datos protegidos con bearer token",
    "edc:contenttype": "application/json"
  },
  "dataAddress": {
    "type": "HttpData",
    "baseUrl": "${ASSET_BASE_URL}",
    "proxyMethod": "true",
    "proxyPath": "true",
    "proxyQueryParams": "true",
    "proxyBody": "true",
    "authKey": "Authorization",
    "secretName": "${SECRET_NAME}"
  }
}
JSON
)

log "Creating asset ${ASSET_ID}"
request POST "/assets" "${ASSET_PAYLOAD}" >/dev/null

POLICY_PAYLOAD=$(cat <<JSON
{
  "@context": [
    "https://w3id.org/edc/connector/management/v0.0.1"
  ],
  "@type": "PolicyDefinition",
  "@id": "${POLICY_ID}",
  "policy": {
    "@type": "Set",
    "permission": [
      {
        "action": "use",
        "constraint": {
          "leftOperand": "MembershipCredential",
          "operator": "eq",
          "rightOperand": "active"
        }
      }
    ]
  }
}
JSON
)

log "Creating policy ${POLICY_ID}"
request POST "/policydefinitions" "${POLICY_PAYLOAD}" >/dev/null

CONTRACT_PAYLOAD=$(cat <<JSON
{
  "@context": [
    "https://w3id.org/edc/connector/management/v0.0.1"
  ],
  "@id": "${CONTRACT_DEF_ID}",
  "@type": "ContractDefinition",
  "accessPolicyId": "${POLICY_ID}",
  "contractPolicyId": "${POLICY_ID}",
  "assetsSelector": {
    "@type": "Criterion",
    "operandLeft": "https://w3id.org/edc/v0.0.1/ns/id",
    "operator": "in",
    "operandRight": [
      "${ASSET_ID}"
    ]
  }
}
JSON
)

log "Creating contract definition ${CONTRACT_DEF_ID}"
request POST "/contractdefinitions" "${CONTRACT_PAYLOAD}" >/dev/null

log "Seed completed successfully."
