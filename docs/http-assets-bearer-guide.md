# Guia HTTP Assets con Bearer Token (MVD)

Esta guia resume como exponer un asset `HttpData` en el **Minimum Viable Dataspace (MVD)** cuando el backend requiere un Bearer Token. Cubre:

- Creacion y actualizacion del asset mediante la Management API.
- Almacenamiento del token en HashiCorp Vault y su posterior rotacion.
- Significado de las propiedades clave (`proxyMethod`, `proxyPath`, `secretName`, etc.).
- Como consumir el recurso a partir de la EndpointDataReference (EDR).

---

## 1. Asset con token incrustado (`authCode`)

Solo recomendado para pruebas rapidas con tokens de corta duracion.

1. Crea un fichero `asset-secure-endpoint.json`:

   ```json
   {
     "@context": {},
     "@id": "asset-secure-endpoint",
     "properties": {
       "edc:name": "Secure API asset",
       "edc:description": "Datos protegidos con bearer token",
       "edc:contenttype": "application/json"
     },
     "dataAddress": {
       "type": "HttpData",
       "baseUrl": "https://api.tu-dominio.com/recurso",
       "proxyMethod": "true",
       "proxyPath": "true",
       "proxyQueryParams": "true",
       "proxyBody": "true",
       "authKey": "Authorization",
       "authCode": "Bearer eyJhbGciOi..."
     }
   }
   ```

2. Registra el asset en el controlplane del proveedor:

   ```bash
   curl -X POST \
     -H "Content-Type: application/json" \
     -H "X-Api-Key: provider-api-key" \
     --data @asset-secure-endpoint.json \
     https://provider-controlplane.mvd/management/v3/assets
   ```

3. Para renovar el token repite el `PUT` solo con el `dataAddress`:

   ```bash
   curl -X PUT \
     -H "Content-Type: application/json" \
     -H "X-Api-Key: provider-api-key" \
     --data '{
       "type": "HttpData",
       "baseUrl": "https://api.tu-dominio.com/recurso",
       "proxyMethod": "true",
       "proxyPath": "true",
       "proxyQueryParams": "true",
       "proxyBody": "true",
       "authKey": "Authorization",
       "authCode": "Bearer <nuevo-token>"
     }' \
     https://provider-controlplane.mvd/management/v3/assets/asset-secure-endpoint/dataaddress
   ```

---

## 2. Token en HashiCorp Vault (`secretName`)

Uso recomendado para entornos reales o rotaciones frecuentes.

1. Ajusta el `dataAddress` para usar `secretName` (sin prefijos):

   ```json
   "dataAddress": {
     "type": "HttpData",
     "baseUrl": "https://api.tu-dominio.com/recurso",
     "proxyMethod": "true",
     "proxyPath": "true",
     "proxyQueryParams": "true",
     "proxyBody": "true",
     "authKey": "Authorization",
     "secretName": "secure-api"
   }
   ```

2. Publica el asset como antes (`POST /management/v3/assets`).

3. Guarda el token en el Vault del proveedor:

   ```bash
   # tunel hacia el Vault del proveedor
   kubectl port-forward svc/provider-vault -n mvd 8200:8200

   export VAULT_ADDR=http://127.0.0.1:8200
   export VAULT_TOKEN=root   # valor por defecto en el despliegue MVD

   # guardar o actualizar el token en la clave "content"
   vault kv put secret/secure-api content="Bearer eyJhbGciOi..."
   ```

   Alternativas:

   - **API HTTP directa**

   ```bash
   curl -X POST http://127.0.0.1:8200/v1/secret/data/secure-api \
        -H "X-Vault-Token: ${VAULT_TOKEN}" \
        -H "Content-Type: application/json" \
        -d '{"data":{"content":"Bearer eyJhbGciOi..."}}'
   ```

   - **Script helper (`store-vault-secret.sh`)**

   ```bash
   # requiere jq y curl instalados
   VAULT_ADDR=http://127.0.0.1:8200 \
   VAULT_TOKEN=root \
   SECRET_NAME=secure-api \
   SECRET_VALUE="Bearer eyJhbGciOi..." \
   ./store-vault-secret.sh
   ```

4. Para revisar o rotar:

   ```bash
   vault kv get secret/secure-api          # ver valor actual
   vault kv put secret/secure-api content="Bearer <nuevo-token>"   # rotar
   ```

   El dataplane siempre usara la version mas reciente sin necesidad de modificar el asset ni reiniciar servicios.

---

## 3. Propiedades del `dataAddress`

Todas las propiedades viven dentro del objeto `dataAddress`. La tabla siguiente resume las mas usadas y ejemplos de valores.

| Propiedad | Descripcion | Ejemplo util |
|-----------|-------------|--------------|
| `type` | Siempre `HttpData` para transferencias HTTP. | "type": "HttpData" |
| `baseUrl` | URL base del backend (sin `/data/<transferId>`). | "baseUrl": "https://api.circularpass.io/api/secure/v1" |
| `path` | Ruta fija concatenada cuando `proxyPath = "false"`. | "path": "/instances/did%3Aweb%3A..." |
| `method` | Metodo HTTP fijo (por defecto `GET`). | "method": "POST" |
| `proxyMethod` | "true" reutiliza el metodo usado por el consumidor. | "proxyMethod": "true" |
| `proxyPath` | "true" concatena al `baseUrl` todo lo que vaya tras `/api/public/`. | "proxyPath": "true" |
| `queryParams` | Parametros estaticos añadidos al backend. | "queryParams": "page=1&pageSize=10" |
| `proxyQueryParams` | Replica los parametros enviados por el consumidor. | "proxyQueryParams": "true" |
| `proxyBody` | "true" reenvia el body y `Content-Type` del consumidor. | "proxyBody": "true" |
| `contentType` | Cabecera fija cuando no proxificas el body. | "contentType": "application/json" |
| `authKey` | Cabecera donde se inyectara el secreto (Bearer, API key...). | "authKey": "Authorization" |
| `authCode` | Valor literal del header (solo pruebas). | "authCode": "Bearer eyJ..." |
| `secretName` | Nombre del secreto en Vault (sin `secret/`). | "secretName": "secure-api" |
| `header:<Nombre>` | Cabeceras adicionales fijas. | "header:Accept": "application/json" |
| `nonChunkedTransfer` | "true" desactiva chunking. | "nonChunkedTransfer": "true" |

### Notas clave

- Para rutas fijas deja `proxyPath = "false"` y define `baseUrl`/`path`.
- Para rutas dinamicas usa `proxyPath = "true"` y deja que el consumidor añada segmentos tras `/api/public/`.
- `authKey` sirve tanto para Bearer como para API keys; `secretName` funciona igual en ambos casos.
- `proxyBody = "true"` implica que el consumidor envia el cuerpo exacto al dataplane (ideal para `POST`).

### Combinaciones frecuentes

1. **GET fijo**
   ```json
   "dataAddress": {
     "type": "HttpData",
     "baseUrl": "https://api.circularpass.io/api/secure/v1/instances",
     "proxyPath": "false",
     "proxyMethod": "false",
     "proxyQueryParams": "false"
   }
   ```

2. **GET dinamico reutilizable**
   ```json
   "dataAddress": {
     "type": "HttpData",
     "baseUrl": "https://api.circularpass.io/api/secure/v1",
     "proxyPath": "true",
     "proxyMethod": "true",
     "proxyQueryParams": "true"
   }
   ```

3. **POST con secreto en Vault**
   ```json
   "dataAddress": {
     "type": "HttpData",
     "baseUrl": "https://api.tu-dominio.com/api/v2/oauth/login",
     "method": "POST",
     "proxyBody": "true",
     "contentType": "application/json",
     "authKey": "Authorization",
     "secretName": "secure-api"
   }
   ```

El consumidor invoca el dataplane con el token de la EDR y el body requerido por la API origen. El dataplane añade el header usando el secreto de Vault.

> Consejo: si tu API no acepta sufijos dinamicos, desactiva `proxyPath` o fija `path` con la ruta exacta.
### Como funciona realmente `proxyPath`

- El dataplane publica todo bajo `.../api/public/**`. Con `proxyPath = "true"` copia literalmente el tramo que vaya **despues de `/api/public/`** y lo concatena al `baseUrl`.
- Ejemplo dinamico: con `baseUrl = https://api.circularpass.io/api/secure/v1` y `proxyPath = "true"`, si el consumidor invoca  
  `GET .../api/public/data/<tpId>/instances/did%3A...`, el dataplane llamara a `https://api.circularpass.io/api/secure/v1/instances/did%3A...`.
- Ejemplo estatico: con `proxyPath = "false"` y `baseUrl = https://api.circularpass.io/api/secure/v1/instances`, cualquier llamada a `.../api/public/...` terminara en `https://api.circularpass.io/api/secure/v1/instances`. Usa este modo cuando tu backend expone una ruta fija (como en el ejemplo de CircularPass sin ruta dinamica).
- Si llamas al dataplane sin añadir nada tras `/api/public/` y tienes `proxyPath = "true"`, el sufijo sera exactamente lo que hayas enviado (p.ej. `data/<tpId>`). Si la API origen no admite ese sufijo, desactiva `proxyPath` o construye la ruta completa en la llamada del consumidor.

---

## 4. Consumir el asset tras la transferencia

1. El consumidor lanza la transferencia (`POST /management/v3/transferprocesses`).
2. Una vez en estado `COMPLETED`, recupera la EDR:

   ```bash
   curl -H "X-Api-Key: consumer-api-key" \
        http://localhost/consumer/cp/api/management/v3/edrs/<transferProcessId>/dataaddress
   ```

   Campos clave:
   - `endpoint`: URL base del dataplane del proveedor (ej. `http://provider-qna-dataplane:11002/api/public`).
   - `authorization`: token temporal que el consumidor debe usar.

3. Para peticiones `PULL`, basta con invocar el endpoint publico. Un formato habitual es `GET {endpoint}/data/{transferProcessId}`, pero en el despliegue MVD puede omitirse el sufijo y llamar directamente a `{endpoint}`.

   ```bash
   curl -X GET \
        http://localhost/provider-qna/public/api/public \
        -H "Authorization: <token-EDR>"
   ```

   El dataplane valida el token de la EDR y realiza la llamada al backend usando `baseUrl` (y `path`/`proxyPath` segun corresponda).

4. Para listar transferencias recientes ordenadas:

   ```bash
   curl -H "X-Api-Key: consumer-api-key" \
        "http://localhost/consumer/cp/api/management/v3/transferprocesses?sort=createdAt&sortOrder=DESC&limit=5"
   ```

   Tambien puedes usar `POST /management/v3/transferprocesses/request` con un `QuerySpec` que incluya `sortField` y `sortOrder`.

---

## 5. Flujo resumido

1. Publicas el asset `HttpData`.
2. Creas la `Policy` y la `ContractDefinition` para exponerlo en el catalogo.
3. El consumidor negocia y recibe la EDR.
4. El consumidor llama al dataplane con el token de la EDR.
5. El dataplane injerta el bearer del Vault en la llamada al backend y retorna la respuesta.

---

## 6. Buenas practicas

- Limita `authCode` a pruebas. En produccion usa `secretName` y Vault.
- Automatiza la rotacion: scripts o jobs que actualicen `vault kv put secret/secure-api content="<nuevo>"`.
- Ajusta `proxyPath` y `proxyMethod` segun lo que acepte tu backend.
- Observa los logs del dataplane (`kubectl logs deployment/<alias>-dataplane -n mvd`) para depurar.
- Tras modificar el asset, inicia una nueva transferencia para validar los cambios.

---

## 7. Referencias

- `extensions/data-plane/data-plane-http/.../BaseCommonHttpParamsDecorator.java`
- `extensions/data-plane/data-plane-http/.../BaseSourceHttpParamsDecorator.java`
- `docs/technical-overview.md`

---

## 8. Script de seed rapido

El script `./seed-secure-asset.sh` registra automaticamente:

- el asset `HttpData` apuntando a `https://api.circularpass.io/api/secure/v1/instances`,
- la policy `require-membership`,
- y la `ContractDefinition` que vincula ambos.

Variables utiles (opcional cambiar antes de ejecutar):

| Variable           | Valor por defecto                                      |
|--------------------|--------------------------------------------------------|
| `BASE_URL`         | `http://127.0.0.1/provider-qna/cp`                     |
| `API_KEY`          | `provider-api-key`                                     |
| `ASSET_BASE_URL`   | `https://api.circularpass.io/api/secure/v1/instances`  |
| `SECRET_NAME`      | `secure-api`                                           |
| `ASSET_ID`         | `asset-secure-endpoint`                                |
| `POLICY_ID`        | `require-membership`                                   |

Uso basico:

```bash
BASE_URL=http://127.0.0.1/provider-qna/cp \
API_KEY=provider-api-key \
./seed-secure-asset.sh
```

El script elimina versiones anteriores (si existen) y crea de nuevo el asset, la policy y la contract definition, dejando el dataspace listo para pruebas con CircularPass.

