# Minimum Viable Dataspace (MVD)

> Guía de onboarding rápida para levantar el entorno de demostración **Minimum Viable Dataspace** basado en Eclipse Dataspace Components (EDC) y el Decentralized Claims Protocol (DCP).

## 1. ¿Qué es este repositorio?
Este repo contiene un escenario de demostración completo para explorar:
- Intercambio de credenciales (DCP) previo al intercambio de mensajes DSP.
- Publicación y consumo de catálogos federados.
- Componentes EDC: Connector (controlplane/dataplane), IdentityHub, Catalog Server, Issuer Service.

No es producción. Es un entorno para experimentar y aprender. Para entender más en profundidad arquitectura, limitaciones y flujo de credenciales:
- Para levantar el entorno rapidamente, revisar 3.1
- Revisa el documento oficial incluido: `README-oficial-EDC.md` (versión adaptada del README original del proyecto MVD).
- Explora la carpeta `docs/` (ej. `technical-overview.md`, `http-assets-bearer-guide.md`).

## 2. Documentación principal
| Recurso | Descripción |
|---------|-------------|
| `README-oficial-EDC.md` | Explicación extensa del escenario, participantes, credenciales, políticas y flujos. |
| `docs/technical-overview.md` | Visión técnica resumida del diseño y componentes. |
| `docs/http-assets-bearer-guide.md` | Guía para registrar y consumir assets HTTP con autenticación Bearer/secretName. |
| `deployment/` | Infraestructura Terraform, assets, módulos y configuración Kind. |
| `launchers/` | Artefactos de runtime (Dockerfiles, configuraciones) usados para construir imágenes locales. |

## 3. Scripts clave
En la raíz encontrarás dos scripts para ciclo de vida en Kubernetes local (Kind):

### 3.1 `run-mvd.sh`
Script de despliegue end-to-end en un clúster **Kind** local.
Fases:
1. (Opcional interactivo) Build + dockerize de imágenes locales (`./gradlew build` y `./gradlew -Ppersistence=true dockerize`).
2. Creación del clúster Kind (si no existe) usando `deployment/kind.config.yaml`.
3. Carga de imágenes locales al clúster (`kind load docker-image ...`).
4. Instalación de Ingress NGINX y espera a que quede Ready.
5. `terraform init` + `terraform apply` en `deployment/` para desplegar Postgres, Vault, runtimes, issuer, etc.
6. Verificación de pods y espera básica de readiness.
7. Ejecución de `seed-k8s.sh` para inicializar participantes, credenciales y datos necesarios.
8. Resumen final (recordatorios de cómo inspeccionar pods y probar endpoints).

Características:
- Detecta si estás en WSL y recomienda mover el repo fuera de `/mnt/c` por performance.
- Verifica dependencias antes de iniciar (fallo rápido si falta algo).
- Pregunta si quieres ejecutar el build (puedes saltarlo si no cambiaste código). 

Salida esperada: un entorno completo listo para probar APIs (`http://127.0.0.1/<provider|consumer|issuer>/...`).

### 3.2 `clean-mvd.sh`
Script de limpieza segura.
Acciones:
1. Confirmación interactiva (se puede forzar con `--yes`).
2. Elimina contenedores Docker relacionados con el nombre del clúster (`mvd`).
3. Borra el clúster Kind si existe (`kind delete cluster`).
4. Limpia estado Terraform y lock file (`terraform.tfstate*`, `.terraform/`, `.terraform.lock.hcl`).

Uso típico: reiniciar el entorno desde cero después de experimentar o liberar recursos.

## 4. Dependencias requeridas
Necesitas instalar las siguientes herramientas antes de ejecutar `run-mvd.sh`:

| Herramienta | Versión mínima | Uso |
|-------------|----------------|-----|
| Git | Última estable | Clonar repositorio. |
| Java JDK | >= 17 (Temurin recomendado) | Compilar componentes EDC. |
| Docker | Última estable | Build y runtime de imágenes. |
| kind | >= 0.20 | Crear clúster Kubernetes local. |
| kubectl | Compatible con la versión de Kind | Gestionar/inspeccionar el clúster. |
| Terraform | >= 1.5 | Despliegue de infraestructura (pods, servicios, ingress, etc.). |
| Node.js | >= 18 | Utilidades de seeding (scripts y Postman/Newman). |
| npm | Empaquetado con Node | Instalar dependencias si fuera necesario. |
| newman | Última | Ejecutar colecciones Postman en fase seed. |
| curl, jq | (preinstalados en la mayoría de distros) | Scripts auxiliares (comprobaciones y seed). |

### 4.1 Instalación rápida

Linux (Debian/Ubuntu):
```bash
sudo apt update
sudo apt install -y git curl jq docker.io openjdk-21-jdk npm
# kind
curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.23.0/kind-linux-amd64 && chmod +x kind && sudo mv kind /usr/local/bin/
# terraform
curl -fsSL https://apt.releases.hashicorp.com/gpg | sudo gpg --dearmor -o /usr/share/keyrings/hashicorp.gpg
echo "deb [signed-by=/usr/share/keyrings/hashicorp.gpg] https://apt.releases.hashicorp.com $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/hashicorp.list
sudo apt update && sudo apt install -y terraform
# newman
sudo npm install -g newman
```


Windows (WSL recomendado):
1. Instala WSL2 y una distro Ubuntu.
2. Desde WSL sigue pasos de Linux.
3. Instala Docker Desktop y activa integración con WSL.
4. Verifica que `docker info` funciona dentro de WSL.

Alternativa nativa (no recomendada para este repo): instalar cada binario (choco/scoop), pero ciertas rutas y scripts están optimizados para entorno Linux/WSL.

### 4.2 Comprobación rápida
Ejecuta:
```bash
docker info
kind version
kubectl version --client
terraform version
java -version
node -v
newman -v
```
Si todos responden sin error, puedes continuar.

## 5. Quickstart
Pasos mínimos para levantar el entorno:
```bash
./run-mvd.sh        # Sigue prompts; ejecuta build si es tu primera vez
# Espera finalización y revisa pods
kubectl get pods -n mvd
# Probar un endpoint (ejemplo, variar según despliegue)
curl -s http://127.0.0.1/provider/health | jq
```
Para limpiar:
```bash
./clean-mvd.sh --yes
```

## 6. Estructura relevante
| Carpeta | Rol |
|---------|-----|
| `deployment/` | Terraform, assets (claves, credenciales), configuración Kind. |
| `launchers/` | Configuraciones y fuentes para construir imágenes locales EDC. |
| `docs/` | Guías complementarias y overview técnico. |
| `tests/` | Casos end2end y performance (cuando se desee extender). |

## 7. Preguntas frecuentes (FAQ)
**¿Necesito siempre ejecutar el build?** Solo si modificaste código o es tu primera vez. Puedes responder "N" al prompt para reutilizar imágenes ya construidas.

**¿Por qué falla en `/mnt/c/` dentro de WSL?** La capa de archivos de Windows es más lenta; puede causar bloqueos en Gradle y operaciones Docker intensivas. Mueve el repo a `$HOME`.

**¿Dónde ajusto puertos/DIDs?** Revisa `deployment/assets/env/` y la configuración Terraform. Cambios de puertos pueden afectar DIDs (`did:web`).

**¿Cómo reinicio solo Terraform?** Ejecuta `terraform destroy` dentro de `deployment/` (si quieres borrar recursos) y luego `terraform apply` de nuevo.

## 8. Próximos pasos sugeridos
- Leer con detalle `README-oficial-EDC.md` para entender el flujo de credenciales.
- Registrar un asset HTTP siguiendo `docs/http-assets-bearer-guide.md`.
- Explorar políticas y catálogos federados.

## 9. Disclaimer
Este entorno es exclusivamente para demostración y aprendizaje. No asumas garantías de estabilidad, seguridad o compatibilidad futura.

---
¿Mejoras sugeridas? Abre un issue o PR.
