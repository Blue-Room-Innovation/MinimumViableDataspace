# Vision Tecnica del Minimum Viable Dataspace

Este documento ofrece una vision rapida de la arquitectura del Minimum Viable Dataspace (MVD), de los servicios que se despliegan, los puertos por defecto, las dependencias necesarias y los pasos recomendados para ejecutarlo. Sirve como guia operativa complementaria al [README principal](../README.md).

## 1. Arquitectura general

El escenario contempla dos participantes (Consumidor y Proveedor) conectados a un "dataspace issuer" que publica el DID raiz de la federacion. Cada participante empaqueta los siguientes servicios:

- **Control plane de EDC**: contratos, catalogos y APIs de gestion.
- **Data plane de EDC**: transferencia y exposicion de datos.
- **Identity Hub**: almacen de credenciales y DIDs, con STS embebido.
- **Secure Token Service (STS)**: emision de tokens OAuth2.
- **Hashicorp Vault (dev)**: almacen de secretos/claves.
- **PostgreSQL**: persistencia de contratos, transferencias e identidad.
- **HTTP server**: datos de ejemplo publicados como API REST.

```
                          +-----------------------------+
                          |  Dataspace Issuer (NGINX)    |
                          |  DID document, puerto 9876   |
                          +--------------+--------------+
                                         |
             +---------------------------+---------------------------+
             |                                                           |
      +------v------+                                             +------v------+
      | Consumidor  |                                             | Proveedor   |
      | ------------|                                             | ------------|
      | Control plane|<------ APIs DSP / gestion ----->| Control plane|
      | Data plane   |                                 | Data plane   |
      | Identity Hub |--- credenciales / STS ---+      | Identity Hub |
      | STS         |                            |      | STS         |
      | Vault       |                            |      | Vault       |
      | Postgres    |                            |      | Postgres    |
      | HTTP server |                            |      | HTTP server |
      +-------------+                            |      +-------------+
                                                 |
                                                 +-- DID compartido --+
```

## 2. Servicios y puertos

| Servicio / componente | Puertos por defecto (demo local) | Descripcion |
|-----------------------|----------------------------------|-------------|
| Control plane         | Consumidor 8080-8085 ? Proveedor 8080-8085 | APIs `/api/management/v3`, `/api/control/v1`, `/api/catalog/v1`, contratos y catalogos. |
| Data plane            | Consumidor 8180 (web) / 8183 (control) ? Proveedor 8180 / 8183 | Transferencias de datos y API publica `/api/public`. |
| Identity Hub          | Consumidor 7080-7084 ? Proveedor 7090-7094 | Gestion de credenciales, DID document y STS embebido. |
| Secure Token Service  | Consumidor 8480-8482 ? Proveedor 8580-8582 | Emision de tokens OAuth2 para los conectores. |
| Vault (modo dev)      | Consumidor 8300 ? Proveedor 8200           | Almacena claves privadas y secretos de configuracion. |
| Postgres (control)    | Consumidor 5433 ? Proveedor 5435           | Persistencia de contratos, assets y transferencias. |
| Postgres (identidad)  | Consumidor 5434 ? Proveedor 5436           | Persistencia del Identity Hub. |
| HTTP server           | 8888                                      | API REST con los datos de ejemplo. |
| UI to Connector       | 3001 (cons.) ? 3000 (prov.)                | Interfaz web para invocar las APIs del connector. |
| Dataspace issuer      | 9876                                      | NGINX que publica el DID raiz alojado en `deployment/assets/issuer`. |

> Las llamadas REST utilizan la clave `password` como `Authorization: Bearer password` o encabezado `x-api-key: password`, tal y como definen los `.env` del proyecto.

## 3. Dependencias necesarias

### Desarrollo local / demo IntelliJ
- JDK 17 o superior.
- Gradle wrapper (incluido en el repositorio).
- Docker Desktop o Docker Engine (para NGINX, Vault, Postgres y contenedores completos).
- Node.js + npm y **Newman** (`npm install -g newman`) para ejecutar el script de seeding.
- IntelliJ IDEA (o VS Code) con soporte Gradle.

### Despliegue en Kubernetes
- Docker o Podman para generar imagenes.
- `kind` y `kubectl`.
- Terraform CLI (los modulos viven en `deployment/modules`).
- Registro de contenedores si vas a publicar imagenes personalizadas.

## 4. Pasos de despliegue

### 4.1 Demo local con IntelliJ
1. Importa el proyecto en IntelliJ y deja que Gradle resuelva dependencias.
2. Arranca el dataspace issuer (NGINX) para servir el DID raiz:
   ```bash
   docker run -d --name mvd-nginx -p 9876:80 --rm \
     -v "$PWD"/deployment/assets/issuer/nginx.conf:/etc/nginx/nginx.conf:ro \
     -v "$PWD"/deployment/assets/issuer/did.docker.json:/var/www/.well-known/did.json:ro \
     nginx:1.27.5
   ```
3. Ejecuta los launchers `consumer-*` y `provider-*` (control plane, data plane, identity hub, STS, http server). Existe una configuracion compuesta `dataspace` que los lanza en orden.
4. Compila las imagenes (opcional) si quieres reutilizarlas en Docker/Kubernetes:
   ```bash
   ./gradlew build
   ./gradlew -Ppersistence=true dockerize
   ```
5. Ejecuta el seeding (requiere Newman):
   ```bash
   ./seed.sh
   ```
6. Comprueba el estado:
   ```bash
   curl http://localhost:8081/api/check/health                     # control plane consumidor
   curl http://localhost:8081/api/management/v3/assets \
     -H "Authorization: Bearer password"
   ```

### 4.2 Ejecucion con imagenes Docker generadas localmente
1. Genera las imagenes como en el punto anterior (`./gradlew build` + `./gradlew -Ppersistence=true dockerize`).
2. Define un `docker-compose.yml` para cada participante usando las imagenes `controlplane:latest`, `dataplane:latest`, `identity-hub:latest`, asi como contenedores de Vault y Postgres (ver ejemplo en el documento).
3. Lanza el compose (`docker compose up -d`) y, cuando los servicios esten en estado saludable, ejecuta `./seed.sh` apuntando a los endpoints expuestos.

### 4.3 Despliegue en Kubernetes
1. Construye las imagenes (`./gradlew build` y `./gradlew -Ppersistence=true dockerize`).
2. Crea un cluster KIND e instala un ingress NGINX:
   ```bash
   kind create cluster --config deployment/kind/kind-config.yaml --name mvd
   kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
   kubectl wait --namespace ingress-nginx --for=condition=ready pod --selector=app.kubernetes.io/component=controller --timeout=120s
   ```
3. Carga las imagenes en KIND y aplica los modulos Terraform de `deployment/modules`:
   ```bash
   kind load docker-image controlplane:latest dataplane:latest identity-hub:latest catalog-server:latest -n mvd
   cd deployment/modules
   terraform init
   terraform apply
   ```
4. Ejecuta el seeding (`../seed.sh`) apuntando a los hostnames/puertos publicados por el ingress.

## 5. Checklist operativo

- Health checks: `GET /api/check/health` en cada control plane, data plane e identity hub.
- Management API: `POST /api/management/v3/assets` con `Authorization: Bearer password`.
- Catalogo: `GET /api/catalog/v1/items` (mismo token).
- DSP callbacks: comprueba que `edc.dsp.callback.address` resuelve desde ambos participantes (ajusta IP si ejecutas en Docker Desktop o K8s).
- Vault: token dev `root`; secretos del STS en `secret/<did>-sts-client-secret`.
- Seeding: ejecutar `seed.sh` cada vez que arranques desde una base limpia para poblar DIDs, credenciales y assets.

## 6. Referencias utiles

- Gu?a detallada: [README](../README.md)
- Script de seeding: [`seed.sh`](../seed.sh)
- Colecci?n Postman y entorno HTTP Client: [`deployment/postman`](../deployment/postman)
- Configuraci?n de los launchers que generan las im?genes: [`EDC-Connector/launchers`](../../EDC-Connector/launchers)
