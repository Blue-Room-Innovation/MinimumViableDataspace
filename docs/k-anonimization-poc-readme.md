senal# K-Anonimization en MVD/EDC: README Tecnico de la PoC

Este documento resume la extension implementada para evaluar y ejecutar k-anonimizacion en el flujo de transferencia de EDC dentro de MVD.

## 1. Se ha identificado el punto o puntos del conector EDC donde resulta viable implementar una extension para evaluar si un asset debe anonimizarse

Se identificaron dos puntos tecnicos viables y complementarios:

1. Control Plane (evaluacion de politica de contrato)

- Punto de extension: registro de una funcion de politica sobre el scope de transferencia.
- Ubicacion principal:
  - extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationControlPlaneExtension.java
  - extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationPolicyFunction.java
- Rol: decidir si la transferencia esta marcada para k-anonimizacion y preparar la señal para Data Plane.

2. Data Plane (ejecucion de transformacion de datos)

- Punto de extension: DataSourceFactory para tipo HttpData dentro del pipeline.
- Ubicacion principal:
  - extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationDataPlaneExtension.java
  - extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationHttpDataSourceFactory.java
  - extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationHttpDataSource.java
- Rol: ejecutar la anonimización real durante la lectura de datos, no en la negociacion contractual.

Conclusion de este punto: la decision de negocio/politica ocurre en CP y la manipulacion de bytes ocurre en DP.

## 2. Se ha definido una propuesta minima de politica o criterio tecnico para marcar un asset como susceptible de k-anonimizacion

La propuesta minima implementada es:

1. Politica de contrato

- Constraint en policy: leftOperand = KAnonymization, operator = EQ, rightOperand = true.
- Si KAnonymization != true (o el constraint no existe), no se activa el comportamiento de anonimizacion.

2. Metadatos del asset (validacion adicional)

- kanon.policyConfigUrl = URL accesible del archivo de politica de anonimización

Regla minima efectiva:

- La anonimizacion se ejecuta SOLO cuando la policy exige KAnonymization=true Y el asset contiene kanon.policyConfigUrl no vacio.
- **Si la policy requiere KAnonymization=true pero el asset no tiene kanon.policyConfigUrl**, la transferencia se rechaza en Control Plane con un error (fail-fast), evitando consumir recursos en una transferencia que no puede completarse correctamente.
- **Nota importante**: El atributo `kAnonimizacion` del asset ya NO se utiliza. La decision se basa unicamente en el constraint de la politica.

## 3. Se ha implementado una extension basica o prueba de concepto que permita evaluar esa condicion sobre un asset

Si, esta implementada y operativa como PoC funcional (no solo esqueleto).

### 3.1 Que se implemento

1. Modulos nuevos

- extensions/k-anonimization-control-plane
- extensions/k-anonimization-data-plane

2. Wiring en build

- settings.gradle.kts incluye ambos modulos.
- launchers/controlplane/build.gradle.kts agrega runtimeOnly(project(":extensions:k-anonimization-control-plane")).
- launchers/dataplane/build.gradle.kts agrega runtimeOnly(project(":extensions:k-anonimization-data-plane")).

3. Extension de Control Plane

- Registra la funcion de politica para KAnonymization en TransferProcessPolicyContext.TRANSFER_SCOPE.
- Resuelve asset desde AssetIndex.
- Lee metadatos desde properties y dataAddress del asset (busca kanon.policyConfigUrl).
- Valida que exista policyConfigUrl cuando la politica requiere KAnonymization=true.
- Si falta policyConfigUrl cuando se requiere anonimizacion: niega la transferencia con error SEVERE.
- Si todo es valido: almacena señal de propagacion (agreementId -> assetId, policyConfigUrl, enabled=true).
- Publica propiedades CP -> DP via DataFlowPropertiesProvider.

4. Extension de Data Plane

- Registra factory para HttpData cuando edc.anonymization.enabled=true y existe edc.anonymization.service.url.
- El DataSource decide si anonimiza usando prioridad:
  - propiedades propagadas (kanon.\*),
  - fallback a metadatos en sourceDataAddress.
- Si no aplica anonimización: reenvia dataset original.
- Si aplica anonimización:
  - descarga dataset y policy desde URL,
  - invoca API externa via multipart,
  - recibe ZIP,
  - extrae entrada dataset*anonymized*_._,
  - retorna stream anonimizado al consumidor.

### 3.2 Caso de uso (teorico)

- Un proveedor publica un asset y lo oferta con contrato que exige KAnonymization=true.
- En la transferencia, CP valida que el asset esta marcado y que la policy de anonimización existe.
- CP propaga las banderas tecnicas al flujo.
- DP ejecuta la anonimización antes de entregar el contenido.

### 3.3 Flujo end-to-end (tecnico)

1. Negociacion y policy evaluation en CP.
2. Funcion KanonimizationPolicyFunction detecta KAnonymization=true.
3. Se obtiene assetId desde ContractAgreement.
4. Se consulta AssetIndex y se lee kanon.policyConfigUrl desde metadatos del asset.
5. Si policyConfigUrl falta, se RECHAZA la transferencia con error (fail-fast).
6. Si todo es valido, se guarda señal en KanonimizationPropagationStore.
7. DataFlowPropertiesProvider toma la señal y la inyecta en properties del data flow.
8. DP recibe DataFlowStartMessage con kanon.enabled, kanon.assetId, kanon.policyConfigUrl.
9. KanonimizationHttpDataSource ejecuta descarga + llamada API + extraccion ZIP.
10. Se retorna dataset anonimizado al consumidor.

### 3.4 Matriz de escenarios y comportamiento

La siguiente tabla documenta el comportamiento del sistema ante diferentes combinaciones de configuracion de politica y asset:

| #   | Policy KAnonymization            | Asset kanon.policyConfigUrl | Comportamiento CP                                 | Comportamiento DP        | Resultado Final               |
| --- | -------------------------------- | --------------------------- | ------------------------------------------------- | ------------------------ | ----------------------------- |
| 1   | `true`                           | ✓ presente y valida         | ✓ Almacena señal, permite transferencia           | ✓ Ejecuta anonimización  | Dataset anonimizado entregado |
| 2   | `true`                           | ✗ ausente o vacia           | ✗ **FALLA** con error SEVERE, niega transferencia | N/A (no llega)           | Transferencia rechazada en CP |
| 3   | `false`                          | ✓ presente                  | ✓ Permite sin señal                               | Retorna dataset original | Dataset original entregado    |
| 4   | `false`                          | ✗ ausente                   | ✓ Permite sin señal                               | Retorna dataset original | Dataset original entregado    |
| 5   | ausente (constraint no definido) | cualquiera                  | N/A (funcion no invocada)                         | Retorna dataset original | Dataset original entregado    |

**Notas importantes:**

- **Escenario #1** (caso de uso principal): Policy requiere anonimizacion Y asset tiene policyConfigUrl → se ejecuta anonimizacion.
- **Escenario #2** (validacion fail-fast): Policy requiere anonimizacion pero falta policyConfigUrl → transferencia rechazada inmediatamente en CP con error SEVERE reportado via `context.reportProblem()`.
- **Escenarios #3, #4, #5**: Cuando no se requiere anonimizacion (policy false o ausente), siempre se retorna el dataset original independientemente de los metadatos del asset.

**Principio de diseño: Fail-fast con configuracion simplificada**

La validación estricta en Control Plane (escenario #2) implementa el principio de "fail-fast": detectar configuraciones incorrectas lo antes posible.
Se requiere definir `kanon.policyConfigUrl` en el asset cuando se vaya a usar anonimizacion.

### 3.5 Ejemplos de body de peticiones (management API)

Los siguientes ejemplos estan adaptados al caso real de esta PoC (mismos IDs, context y URLs propuestos).

#### 3.5.1 Crear asset

```json
{
  "@context": ["https://w3id.org/edc/connector/management/v0.0.1"],
  "@id": "asset-kanon-demo-1",
  "@type": "Asset",
  "properties": {
    "edc:name": "KAnon demo asset",
    "edc:description": "PoC asset para deteccion de KAnonymization en control-plane",
    "edc:contenttype": "application/csv"
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

#### 3.5.2 Crear politica de acceso

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

#### 3.5.3 Crear politica de contrato

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

#### 3.5.4 Crear definicion de contrato

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

## 4. Se ha identificado que informacion debe vivir en la politica, en los metadatos del asset o en ambos

Separacion recomendada e implementada:

1. En la politica (contrato)

- KAnonymization=true como requisito normativo del intercambio.
- Semantica: "este intercambio exige anonimización".

2. En metadatos del asset

- kanon.policyConfigUrl con el recurso tecnico de configuracion de anonimización.

3. En ambos

- La activacion final requiere ambos lados:
  - voluntad contractual (policy),
  - viabilidad tecnica y parametros (asset metadata).

Esto evita forzar anonimización sin contexto contractual y evita contratos imposibles de ejecutar tecnicamente.

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

- el store CP de propagacion es in-memory y efimero.
- para escenarios distribuidos se requiere mecanismo persistente o correlacion robusta entre nodos.

4. Contrato de integracion con servicio externo

- versionado de API, validaciones de formato, codigos de error estables.
- garantia de estructura de ZIP y convencion de nombres de salida.

5. Observabilidad

- metricas de latencia, tasa de error, throughput y tamaño procesado.
- correlacion de requestId/processId/agreementId entre CP, DP y servicio de anonimización.

## 6. Se han identificado dependencias, riesgos y limitaciones tecnicas de la aproximacion

### 6.1 Dependencias

1. EDC runtime y APIs usadas

- PolicyEngine, RuleBindingRegistry, TransferProcessPolicyContext
- DataFlowPropertiesProvider
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

1. Propagacion CP->DP basada en store en memoria (no distribuida).
2. Convencion fija de archivo esperado en ZIP: dataset*anonymized*_._.
3. Soporte centrado en HttpData.
4. Sin mecanismo avanzado de retries/backoff/circuit breaker en esta iteracion.

## 7. Se ha generado una conclusion tecnica con recomendacion de siguiente paso: continuar, replantear o desacoplar la anonimización fuera del conector

Conclusion tecnica:

- La aproximacion es viable y ya demostro factibilidad end-to-end dentro del conector.
- El patron CP decide y DP transforma es correcto para EDC.

Recomendacion:

- Continuar, pero con hardening tecnico antes de escalar a produccion.

Plan sugerido de siguiente paso:

1. Endurecer la integracion DP con patrones de resiliencia y control de tamaño/streaming.
2. Reemplazar store in-memory por mecanismo de propagacion/correlacion apto para despliegues distribuidos.
3. Formalizar contrato tecnico con el servicio de anonimización (versionado, errores, payloads, SLA).
4. Incorporar observabilidad completa y pruebas de carga/fallo.

Si el objetivo futuro exige alta independencia operativa o multiples algoritmos de anonimización, se puede evaluar desacoplar parte del procesamiento en un servicio especializado, manteniendo en EDC la decision de politica y la orquestacion del flujo.

## 8. Limpieza de codigo y estrategia de logs (trazabilidad)

Se simplifico el codigo para mantener funcionalidad y reducir ruido de observabilidad.

### 8.1 Logs que se conservan

1. Deteccion de anonimización (true/false)

- CP: decision de politica/metadata en evaluacion de contrato.
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

### Control Plane

- extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationControlPlaneExtension.java
- extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationPolicyFunction.java
- extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationDataFlowPropertiesProvider.java
- extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationPropagationStore.java
- extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationAssetMetadataReader.java

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
