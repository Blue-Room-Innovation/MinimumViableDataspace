# Guía para exponer assets HTTP con Bearer Token en el MVD

Este documento describe cómo publicar un asset `HttpData` en el entorno **Minimum Viable Dataspace (MVD)** desplegado en Kubernetes, de forma que el dataplane pueda acceder a un endpoint protegido con **Bearer Token**. Incluye:

- Creación y actualización del asset usando la Management API.
- Gestión del token (temporal o persistente) mediante HashiCorp Vault.
- Consideraciones para peticiones `GET` y `POST`, incluyendo el tratamiento del `body`.

---

## 1. Crear el asset con token incrustado (`authCode`)

Úsalo únicamente para pruebas rápidas con tokens de corta duración.

1. Preparar el JSON del asset (guárdalo como `asset-secure-endpoint.json`):

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
       "authCode": "Bearer eyJhbGciOi..."       // token temporal pegado a mano
     }
   }
   ```

2. Registrar el asset en el controlplane del proveedor:

   ```bash
   curl -X POST \
     -H "Content-Type: application/json" \
     -H "X-Api-Key: provider-api-key" \
     --data @asset-secure-endpoint.json \
     https://provider-controlplane.mvd/management/v3/assets
   ```

3. Cada vez que el token caduque, actualiza solo la sección del `dataAddress`:

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

## 2. Gestionar el token con HashiCorp Vault (`secretName`)

Recomendado para entornos persistentes o tokens de rotación frecuente.

1. Reutiliza el asset anterior cambiando `authCode` por `secretName`:

   ```json
   "dataAddress": {
     "type": "HttpData",
     "baseUrl": "https://api.tu-dominio.com/recurso",
     "proxyMethod": "true",
     "proxyPath": "true",
     "proxyQueryParams": "true",
     "proxyBody": "true",
     "authKey": "Authorization",
     "secretName": "secret/secure-api"
   }
   ```

2. Publica el asset con `POST /management/v3/assets` igual que antes.

3. Carga el token en el Vault del proveedor:

   ```bash
   # abre un túnel local contra el vault del proveedor
   kubectl port-forward svc/provider-vault -n mvd 8200:8200

   # exporta el token raíz que Terraform mostró tras el despliegue
   export VAULT_ADDR=http://127.0.0.1:8200
   export VAULT_TOKEN=$(terraform -chdir=deployment output -raw provider_vault_root_token)

   # guarda o actualiza el bearer token
   vault kv put secret/secure-api token="Bearer eyJhbGciOi..."
   ```

   El dataplane resolverá automáticamente `secret/secure-api`, buscará la clave `token` y añadirá el header `Authorization: Bearer …` en cada transferencia.

4. Para renovar el token solo repite `vault kv put`; el asset no necesita cambios.

---

## 3. Soporte de métodos HTTP y cuerpo de la petición

El dataplane ejecuta la petición al backend según la configuración del `dataAddress`.

- `method`: define el método fijo (por defecto `GET`).  
  ```json
  "method": "POST"
  ```

- `proxyMethod`: cuando es `"true"` el dataplane reutiliza el método que recibe del consumidor (aplica a `GET`, `POST`, etc.).

- `proxyBody`: con valor `"true"` el dataplane reenvía el cuerpo que envíe el consumidor. Si es `"false"` no se enviará `body`.

- `proxyQueryParams`: propaga los parámetros de query que el consumidor añada al invocar la EDR.

### Ejemplo para POST con payload

```json
"dataAddress": {
  "type": "HttpData",
  "baseUrl": "https://api.tu-dominio.com/consulta",
  "authKey": "Authorization",
  "secretName": "secret/secure-api",
  "method": "POST",
  "proxyBody": "true",
  "proxyQueryParams": "true"
}
```

La aplicación consumidora puede entonces llamar al dataplane:

```bash
curl -X POST https://<provider-dataplane>/data/<transferId> \
     -H "Authorization: <token-EDR>" \
     -H "Content-Type: application/json" \
     -d '{"pedidoId":123,"items":[...] }'
```

El dataplane enviará ese mismo JSON al backend, inyectando internamente el bearer almacenado en Vault o en `authCode`.

---

## 4. Flujo completo resumido

1. **Asset disponible**: datos del endpoint protegidos mediante `HttpData`.
2. **Contrato aprobado**: se emite una EndpointDataReference (EDR) con URL y credenciales temporales para el dataplane.
3. **Cliente consumidor**: invoca la URL de la EDR (GET o POST) proporcionando los headers/cuerpo necesarios.
4. **Dataplane proveedor**: construye la petición hacia el backend usando `baseUrl`, añade `Authorization` con el token recuperado y devuelve la respuesta (`JSON`, `CSV`, etc.) al consumidor.

---

## 5. Consejos y buenas prácticas

- Usa `authCode` solo en pruebas; para producción delega en Vault (`secretName`).
- Rotación de tokens: automatiza `vault kv put` o integra OAuth2 (consulta la extensión `oauth2-provision` del EDC).
- Ajusta `contentType` si el backend requiere algo distinto a `application/json`.
- Monitoriza los logs del dataplane (`kubectl logs deployment/provider-dataplane -n mvd`) para depurar errores de autenticación o llamadas fallidas.
- Tras crear el asset, recuerda definir la `Policy` y `ContractDefinition` que lo harán visible en el catálogo del proveedor.

---

## 6. Referencias útiles

- `extensions/data-plane/data-plane-http/.../BaseCommonHttpParamsDecorator.java`: inyección de headers y resolución de secretos.
- `extensions/data-plane/data-plane-http/.../BaseSourceHttpParamsDecorator.java`: soporte de métodos, cuerpo y query parameters.
- `docs/technical-overview.md`: arquitectura general del MVD.

