## K-Anonimization en MVD/EDC: README Tecnico de la PoC (DP + CP minimo)

Este documento resume la extension implementada para ejecutar k-anonimizacion en el flujo de transferencia de EDC dentro de MVD.

Estado actual de la PoC:

- La ejecucion de anonimización vive en Data Plane.
- El Control Plane incluye una extension minima para registrar/bindear el operando `KAnonymization`.
- No se utilizan senales CP->DP para activar la anonimización.
- El atributo `kAnonimizacion` puede mantenerse como metadato informativo del asset, pero no participa en la logica de activacion.

## 1. Punto del conector donde se implementa la extension

La implementacion se divide en dos piezas con responsabilidades distintas:

1. Control Plane (extension minima de binding)

- Punto de extension: registro de binding/function de policy para `KAnonymization` en scope de transferencia.
- Ubicacion principal:
  - `extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationControlPlaneExtension.java`
- Rol: permitir que la policy custom con `leftOperand=KAnonymization` sea aceptada/evaluable por EDC.
- Alcance: no decide ni propaga activacion tecnica a DP.

2. Data Plane (ejecucion de transformacion de datos)

- Punto de extension: `DataSourceFactory` para tipo `HttpData` dentro del pipeline.
- Ubicacion principal:
  - `extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationDataPlaneExtension.java`
  - `extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationHttpDataSourceFactory.java`
  - `extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationHttpDataSource.java`
- Rol: detectar si hay que anonimizar y, en caso afirmativo, transformar el dataset antes de entregarlo al consumer.

Conclusion de este punto: el CP cubre gobernanza minima del operando y el DP ejecuta toda la anonimización real.

## 2. Criterio tecnico minimo para activar anonimización

Criterio minimo implementado actualmente:

1. Metadatos tecnicos en `dataAddress`

- `kanon.policyConfigUrl` debe existir y ser no vacio.

2. Decision operativa

- Si `kanon.policyConfigUrl` existe: se anonimiza.
- Si `kanon.policyConfigUrl` no existe: se entrega el dataset original.

Notas sobre otros campos:

- `kAnonimizacion=true` puede existir en `properties` del asset como etiqueta informativa/catálogo.
- El constraint de contrato `KAnonymization=true` puede usarse como semantica de gobernanza; su binding/function existe en CP minimo, pero no activa la logica tecnica en DP.

## 3. Implementacion de la PoC

Si, esta implementada y operativa como PoC funcional.

### 3.1 Que se implemento

1. Modulos activos

- `extensions/k-anonimization-control-plane` (minimo, solo binding/function)
- `extensions/k-anonimization-data-plane`

2. Wiring en build

- `settings.gradle.kts` incluye `:extensions:k-anonimization-control-plane` y `:extensions:k-anonimization-data-plane`.
- `launchers/controlplane/build.gradle.kts` agrega `runtimeOnly(project(":extensions:k-anonimization-control-plane"))`.
- `launchers/dataplane/build.gradle.kts` agrega `runtimeOnly(project(":extensions:k-anonimization-data-plane"))`.

3. Extension minima de Control Plane

- Registra binding de `KAnonymization` sobre `TransferProcessPolicyContext.TRANSFER_SCOPE`.
- Registra una function minima para `KAnonymization` (operator `eq`, rightOperand `true`) sin propagacion de propiedades a DP.
- Objetivo: evitar errores de tipo `leftOperand ... is not bound` al definir politicas de contrato.

4. Extension de Data Plane

- Registra factory para `HttpData` cuando `edc.anonymization.enabled=true` y existe `edc.anonymization.service.url`.
- `KanonimizationHttpDataSource` decide anonimización solo con `sourceDataAddress.kanon.policyConfigUrl`.
- Si no aplica anonimización: reenvia dataset original.
- Si aplica anonimización:
  - descarga dataset y policy desde URL,
  - invoca API externa via multipart,
  - recibe ZIP,
  - extrae `dataset_anonymized.*` (con prioridad por formato y fallback por patron),
  - retorna stream anonimizado al consumidor.

### 3.2 Caso de uso (teorico)

- Un proveedor publica un asset `HttpData`.
- El `dataAddress` contiene `kanon.policyConfigUrl`.
- Durante la transferencia, DP detecta esa URL y ejecuta anonimización antes de entregar contenido.

### 3.3 Flujo end-to-end (tecnico)

1. Se inicia transferencia con `HttpData`.
2. DP recibe `DataFlowStartMessage`.
3. `KanonimizationHttpDataSource` lee `baseUrl` y `kanon.policyConfigUrl` desde `sourceDataAddress`.
4. Si `kanon.policyConfigUrl` no existe: entrega dataset original.
5. Si existe:
   - descarga dataset,
   - descarga policy,
   - llama al servicio de anonimización,
   - extrae el dataset anonimizado del ZIP,
   - entrega resultado anonimizado al consumer.

### 3.4 Ejemplos de body de peticiones (management API)

Los siguientes ejemplos estan adaptados al caso real de esta PoC (mismos IDs, context y URLs propuestos).

#### 3.4.1 Crear asset

```json
{
  "@context": ["https://w3id.org/edc/connector/management/v0.0.1"],
  "@id": "asset-kanon-demo-1",
  "@type": "Asset",
  "properties": {
    "edc:name": "KAnon demo asset",
    "edc:description": "PoC asset para anonimización en data-plane",
    "edc:contenttype": "application/csv",
    "kAnonimizacion": "true"
  },
  "dataAddress": {
    "type": "HttpData",
    "baseUrl": "http://host.docker.internal:8088/medical_example.csv",
    "proxyMethod": "true",
    "proxyPath": "true",
    "proxyQueryParams": "true",
    "proxyBody": "true",
    "kanon.policyConfigUrl": "http://host.docker.internal:8088/medical_example_policy.json"
  }
}
```

Nota: en el modo actual, `kAnonimizacion` es opcional e informativo; la activacion tecnica depende de `dataAddress.kanon.policyConfigUrl`.

#### 3.4.2 Crear politica de acceso

```json
{
  "@context": ["https://w3id.org/edc/connector/management/v0.0.1"],
  "@type": "PolicyDefinition",
  "@id": "access-require-membership-kanon-demo",
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
```

#### 3.4.3 Crear politica de contrato

```json
{
  "@context": ["https://w3id.org/edc/connector/management/v0.0.1"],
  "@type": "PolicyDefinition",
  "@id": "contract-require-kanonymization-demo",
  "policy": {
    "@type": "Set",
    "permission": [
      {
        "action": "use",
        "constraint": {
          "leftOperand": "KAnonymization",
          "operator": "eq",
          "rightOperand": "true"
        }
      }
    ]
  }
}
```

Nota: el operando custom `KAnonymization` ahora vuelve a estar registrado en CP minimo para evitar errores de binding/function.

#### 3.4.4 Crear definicion de contrato

```json
{
  "@context": ["https://w3id.org/edc/connector/management/v0.0.1"],
  "@type": "ContractDefinition",
  "@id": "contractdef-kanon-demo-1",
  "accessPolicyId": "access-require-membership-kanon-demo",
  "contractPolicyId": "contract-require-kanonymization-demo",
  "assetsSelector": {
    "@type": "Criterion",
    "operandLeft": "https://w3id.org/edc/v0.0.1/ns/id",
    "operator": "=",
    "operandRight": "asset-kanon-demo-1"
  }
}
```

Notas practicas:

1. Mantener consistencia entre IDs de asset/policies/contract definition.
2. En entornos Docker/Kind usando servicio en host, usar host.docker.internal en URLs.
3. Si el runtime corre directamente en host, reemplazar host.docker.internal por localhost.
4. Segun version/configuracion de management API, assetsSelector puede aceptarse como objeto Criterion o como arreglo de Criterion. Si tu endpoint rechaza objeto, envolverlo en una lista.

## 4. Donde vive cada tipo de informacion (estado actual)

1. Politica de contrato

- `KAnonymization=true` representa gobernanza/semantica contractual.
- El operando esta registrado y evaluable en CP minimo.
- No propaga senales a DP ni controla tecnicamente la llamada al servicio externo.

2. Metadatos del asset

- `kAnonimizacion=true`: informativo (catalogo/documentacion).
- `kanon.policyConfigUrl` en `dataAddress`: parametro tecnico efectivo para activar anonimización.

3. Regla tecnica real aplicada

- Activacion por presencia de `kanon.policyConfigUrl` en `HttpData`.

## 5. Se ha documentado la dificultad tecnica de evolucion desde esta extension basica hacia una anonimización real de datos

Dificultad estimada: media-alta.

Ya se implemento una anonimización real basica (llamada HTTP externa + ZIP), pero para evolucionar a un nivel productivo se requiere:

1. Robustez operativa

- retries con backoff, circuit breaker, timeout diferenciado por etapa (download/policy/api).
- control de tamaño de archivos y streaming para datasets grandes.

2. Seguridad

- gestion de secretos via vault y rotacion de API keys.
- endurecimiento de acceso a policy URLs y dataset URLs.
- trazabilidad y redaccion de logs sensibles.

3. Consistencia y estado

- al no depender de store CP de propagacion, se reduce acoplamiento entre planos.
- la consistencia depende de que el `dataAddress` tenga metadatos correctos (`baseUrl`, `kanon.policyConfigUrl`).

4. Contrato de integracion con servicio externo

- versionado de API, validaciones de formato, codigos de error estables.
- garantia de estructura de ZIP y convencion de nombres de salida.

5. Observabilidad

- metricas de latencia, tasa de error, throughput y tamaño procesado.
- correlacion de requestId/processId/agreementId entre DP y servicio de anonimización.

## 6. Se han identificado dependencias, riesgos y limitaciones tecnicas de la aproximacion

### 6.1 Dependencias

1. EDC runtime y APIs usadas

- PolicyEngine, RuleBindingRegistry, TransferProcessPolicyContext (solo extension CP minima de binding/function)
- PipelineService, DataSourceFactory, DataFlowStartMessage

2. Servicio externo de anonimización

- Endpoint configurable por edc.anonymization.service.url
- Credenciales via edc.anonymization.service.apiKeyId y edc.anonymization.service.apiKeySecret

3. Configuracion de entorno

- deployment/assets/env/provider_connector_qna.env
- deployment/assets/env/provider_connector_manufacturing.env

### 6.2 Riesgos

1. Dependencia fuerte de disponibilidad del servicio externo.
2. Posibles fallos por URLs no alcanzables o politicas mal formadas.
3. Posible crecimiento de memoria por manejo de archivos en byte[] (dataset/policy/zip).
4. Riesgo de fugas de secretos si se configuraran logs inadecuados.

### 6.3 Limitaciones actuales

1. Activacion basada en `kanon.policyConfigUrl` en `dataAddress` (si falta o es invalida, no anonimiza o falla llamada externa).
2. Convencion fija de archivo esperado en ZIP: `dataset_anonymized.*` con fallback por patron.
3. Soporte centrado en `HttpData`.
4. Sin mecanismo avanzado de retries/backoff/circuit breaker en esta iteracion.

## 7. Se ha generado una conclusion tecnica con recomendacion de siguiente paso: continuar, replantear o desacoplar la anonimización fuera del conector

Conclusion tecnica:

- La aproximacion es viable y ya demostro factibilidad end-to-end dentro del conector.
- El enfoque actual mantiene CP minimo para gobernanza de policy y DP para ejecucion tecnica de anonimización.

Recomendacion:

- Continuar, pero con hardening tecnico antes de escalar a produccion.

Plan sugerido de siguiente paso:

1. Endurecer la integracion DP con patrones de resiliencia y control de tamaño/streaming.
2. Formalizar contrato tecnico con el servicio de anonimización (versionado, errores, payloads, SLA).
3. Incorporar observabilidad completa y pruebas de carga/fallo.
4. Decidir si `KAnonymization=true` debe permanecer como gobernanza declarativa o volver a conectarse con reglas tecnicas mas estrictas.

Si el objetivo futuro exige alta independencia operativa o multiples algoritmos de anonimización, se puede evaluar desacoplar parte del procesamiento en un servicio especializado, manteniendo en EDC la decision de politica y la orquestacion del flujo.

En el estado actual, EDC Data Plane ejecuta el procesamiento de forma autocontenida, mientras el Control Plane solo resuelve el binding/function de policy custom.

## 8. Limpieza de codigo y estrategia de logs (trazabilidad)

Se simplifico el codigo para mantener funcionalidad y reducir ruido de observabilidad.

### 8.1 Logs que se conservan

1. Deteccion de anonimización (true/false)

- DP: decision final por transferencia (`detection=true` o `detection=false`).

2. Llamada al endpoint de k-anonimizacion

- Log con endpoint, formato de dataset y si hay auth configurada.

3. Respuesta del endpoint de k-anonimizacion

- Log con status HTTP y tamano de body recibido.

4. Errores

- Errores de descarga, llamada HTTP y extraccion ZIP.
- En errores de ZIP se incluyen entries encontradas para diagnostico.

### 8.2 Logs eliminados por ruido o riesgo

1. Logs de credenciales en claro (apiKeyId/apiKeySecret).
2. Logs redundantes del request builder y checks duplicados.
3. Logs de bajo valor operativo en startup que no aportaban trazabilidad funcional.

### 8.3 Simplificaciones de codigo realizadas

1. Se centralizo la decision de auth configurada en un helper (`hasCredentials`).
2. Se redujeron logs repetidos en el cliente HTTP.
3. Se mantuvo una ruta de extraccion ZIP clara:

- archivo esperado segun formato (`dataset_anonymized.csv`, `dataset_anonymized.json`, `dataset_anonymized.xlsx`),
- fallback `dataset_anonymized*` para compatibilidad.

### 8.4 Compatibilidad de formatos de salida

La extraccion de resultado anonimizado soporta:

1. CSV

- Prioridad: `dataset_anonymized.csv`

2. JSON

- Prioridad: `dataset_anonymized.json`

3. Excel

- Prioridad: `dataset_anonymized.xlsx`
- Fallback por patron para variantes (`dataset_anonymized*.xls*`)

---

## Anexo A - Archivos clave implementados

### Control Plane (minimo)

- extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationControlPlaneExtension.java

### Data Plane

- extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationDataPlaneExtension.java
- extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationHttpDataSourceFactory.java
- extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationHttpDataSource.java
- extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationServiceClient.java

### Wiring y entorno

- settings.gradle.kts
- launchers/controlplane/build.gradle.kts
- launchers/dataplane/build.gradle.kts
- deployment/assets/env/provider_connector_qna.env
- deployment/assets/env/provider_connector_manufacturing.env
