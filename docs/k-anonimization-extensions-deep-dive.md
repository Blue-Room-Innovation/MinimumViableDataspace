# K-Anonimization: Deep Dive Tecnico de las Extensiones CP y DP

Este documento extiende [docs/k-anonimization-poc-readme.md](docs/k-anonimization-poc-readme.md).

El README funcional explica el caso de uso, la policy minima y el comportamiento esperado. Este documento continua a partir de ahi y baja al nivel de implementacion:

- clases Java implicadas,
- responsabilidades por modulo,
- flujo interno exacto CP -> DP,
- semantica actual de `kanon.enabled`,
- compatibilidad con el comportamiento legacy,
- y limitaciones tecnicas observables en el codigo.

## 1. Resumen de la arquitectura interna

La implementacion esta dividida en dos extensiones:

- `extensions/k-anonimization-control-plane`
- `extensions/k-anonimization-data-plane`

Su reparto de responsabilidades es este:

- Control Plane: interpreta la policy del contrato y propaga la intencion de anonimizar.
- Data Plane: ejecuta la transformacion real sobre el dataset entregado.

Tras el cambio reciente, la semantica correcta queda separada en dos capas:

- señal contractual: `kanon.enabled`
- parametro tecnico: `kanon.policyConfigUrl`

## 2. Como se cargan las extensiones

### 2.1 Inclusion en Gradle

El proyecto incluye ambos modulos en `settings.gradle.kts`.

### 2.2 Wiring en launchers

- `launchers/controlplane/build.gradle.kts` carga `:extensions:k-anonimization-control-plane`
- `launchers/dataplane/build.gradle.kts` carga `:extensions:k-anonimization-data-plane`

### 2.3 Entry points reales

Las clases de entrada son:

- `KanonimizationControlPlaneExtension`
- `KanonimizationDataPlaneExtension`

Ambas implementan `ServiceExtension`, por lo que EDC ejecuta su `initialize(ServiceExtensionContext context)` al arrancar el runtime correspondiente.

## 3. Flujo tecnico end-to-end actualizado

El flujo interno real ahora es este:

1. Existe una policy con `KAnonymization == true`.
2. El Control Plane evalua esa policy durante la transferencia.
3. `KanonimizationPolicyFunction` detecta que la policy exige anonimización.
4. El CP intenta enriquecer la señal con `assetId` y `kanon.policyConfigUrl`.
5. Aunque falte `kanon.policyConfigUrl`, el CP sigue propagando `kanon.enabled=true`.
6. `KanonimizationDataFlowPropertiesProvider` construye las propiedades del data flow.
7. El DP recibe un `DataFlowStartMessage` con `kanon.enabled` y, si existe, con `kanon.policyConfigUrl`.
8. `KanonimizationHttpDataSource` decide si anonimiza usando `kanon.enabled` como señal primaria.
9. Si `kanon.enabled=true` y falta `kanon.policyConfigUrl`, el DP falla al intentar ejecutar la anonimización.
10. Si `kanon.enabled=true` y la URL existe, el DP llama al servicio externo y devuelve el resultado anonimizado.

## 4. Control Plane en detalle

## 4.1 KanonimizationControlPlaneExtension

Archivo: `extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationControlPlaneExtension.java`

### Responsabilidad

Esta clase hace el wiring del modulo de CP:

1. crea el store temporal de propagacion;
2. crea los helpers para resolver assets y leer metadatos;
3. registra la policy function en el motor de policies;
4. publica el `DataFlowPropertiesProvider` que actua como puente hacia DP.

### Dependencias inyectadas

- `PolicyEngine`
- `RuleBindingRegistry`
- `AssetIndex`
- `Monitor`

### Idea clave

No ejecuta anonimización ni valida payloads. Solo registra la capacidad de interpretar `KAnonymization` y de propagar la señal al Data Plane.

## 4.2 KanonimizationPolicyFunction

Archivo: `extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationPolicyFunction.java`

Es la clase que materializa la semantica contractual.

### Interfaz

Implementa `AtomicConstraintRuleFunction<Permission, TransferProcessPolicyContext>`.

### Que hace ahora `evaluate(...)`

#### Paso 1. Valida el operador

Solo admite `Operator.EQ`.

Si la policy usa otro operador, reporta problema y devuelve `false`.

#### Paso 2. Interpreta el valor del constraint

Si el valor no equivale a `true`, devuelve `true` y no activa la señal.

Es decir:

- no hay error,
- simplemente no hay anonimización.

#### Paso 3. Intenta enriquecer la señal

Si `KAnonymization=true`, intenta:

- resolver `assetId` desde el `ContractAgreement`;
- buscar el asset en `AssetIndex`;
- leer `kanon.policyConfigUrl` desde el asset.

### Cambio semantico importante

Antes:

- si faltaba `kanon.policyConfigUrl`, el CP rechazaba la transferencia.

Ahora:

- el CP sigue propagando `enabled=true` aunque falte `kanon.policyConfigUrl`.

Eso significa que la policy function ya no usa la URL como condicion para activar la señal contractual.

### Paso 4. Persistencia de la señal CP -> DP

La clase guarda en `KanonimizationPropagationStore` una señal con:

- `enabled=true`
- `assetId` si se ha podido resolver
- `policyConfigUrl` si se ha podido leer

Si falta `agreementId`, no puede guardar la señal y deja warning.

### Consecuencia tecnica

Esta clase ya no responde a la pregunta "puedo anonimizar tecnicamente ahora mismo".

Responde a esta otra:

- "la policy de contrato exige que esta transferencia vaya por la ruta de anonimización".

## 4.3 KanonimizationPolicyContextHelper

Archivo: `extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationPolicyContextHelper.java`

Pequeño helper que extrae `assetId` desde `context.contractAgreement()`.

Su funcion es puramente utilitaria.

## 4.4 KanonimizationAssetResolver

Archivo: `extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationAssetResolver.java`

Wrapper ligero sobre `AssetIndex.findById(assetId)`.

No añade logica de negocio, pero desacopla la policy function del acceso directo al indice.

## 4.5 KanonimizationAssetMetadataReader

Archivo: `extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationAssetMetadataReader.java`

### Que busca

Busca `kanon.policyConfigUrl` en distintas variantes de nombres:

- `kanon.policyConfigUrl`
- `edc:kanon.policyConfigUrl`
- `https://w3id.org/edc/v0.0.1/ns/kanon.policyConfigUrl`

### Donde busca

Inspecciona:

1. `properties`
2. `privateProperties`
3. `dataAddress.properties`

### Particularidad tecnica

Usa reflexion para leer getters del asset y del data address.

Ventaja:

- acoplamiento bajo con el tipo concreto del asset.

Riesgo:

- si cambia la estructura del objeto, puede devolver `null` silenciosamente.

## 4.6 KanonimizationPropagationStore

Archivo: `extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationPropagationStore.java`

### Funcion

Guarda temporalmente la señal de anonimización hasta que EDC construye el data flow.

### Estructura

- `ConcurrentHashMap<String, KanonimizationSignal> byAgreementId`

### Significado actual del record `KanonimizationSignal`

- `enabled`: señal contractual derivada de la policy
- `assetId`: contexto adicional de la transferencia
- `policyConfigUrl`: parametro tecnico opcional para el DP

### Limitacion

Sigue siendo un store en memoria local, por tanto adecuado para PoC pero no para despliegue distribuido endurecido.

## 4.7 KanonimizationDataFlowPropertiesProvider

Archivo: `extensions/k-anonimization-control-plane/src/main/java/org/eclipse/edc/demo/kanon/controlplane/KanonimizationDataFlowPropertiesProvider.java`

Es el puente final CP -> DP.

### Metodo `propertiesFor(...)`

Pasos:

1. Obtiene `agreementId` del `TransferProcess`.
2. Consume la señal desde el store con `remove(...)`.
3. Si la señal no existe o `enabled=false`, devuelve mapa vacio.
4. Si existe, propaga:
   - `kanon.enabled` siempre,
   - `kanon.assetId` solo si existe,
   - `kanon.policyConfigUrl` solo si existe.

### Cambio semantico relevante

`kanon.enabled` es ahora la propiedad central del contrato entre CP y DP.

La presencia de `kanon.policyConfigUrl` ya no define por si sola que haya anonimización; solo completa la configuracion de ejecucion.

## 5. Data Plane en detalle

## 5.1 KanonimizationDataPlaneExtension

Archivo: `extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationDataPlaneExtension.java`

### Funcion

Inicializa el modulo de DP y registra la factory para `HttpData` cuando la funcionalidad esta habilitada.

### Settings soportadas

- `edc.anonymization.enabled`
- `edc.anonymization.service.url`
- `edc.anonymization.service.apiKeyId`
- `edc.anonymization.service.apiKeySecret`
- `edc.anonymization.timeout.seconds`

### Detalle practico

La extension soporta variantes de nombres para las credenciales, lo que ayuda a convivir con distintas fuentes de configuracion.

## 5.2 KanonimizationHttpDataSourceFactory

Archivo: `extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationHttpDataSourceFactory.java`

### Funcion

Convierte un `DataFlowStartMessage` en un `KanonimizationHttpDataSource`.

### Que valida

Exige que `sourceDataAddress` tenga `baseUrl`.

### Papel en el flujo

No toma decisiones de anonimización. Solo transporta hacia el `DataSource`:

- `sourceDataAddress`
- ids del flujo
- propiedades propagadas por el CP

## 5.3 KanonimizationHttpDataSource

Archivo: `extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationHttpDataSource.java`

Es la clase central del Data Plane.

### Cambio semantico principal

Antes:

- el DP decidia por la presencia de `kanon.policyConfigUrl`.

Ahora:

- el DP decide por `kanon.enabled`.

### Logica actual de `openPartStream()`

#### Paso 1. Lee `baseUrl`

Si falta, falla inmediatamente.

#### Paso 2. Resuelve la señal de activacion

Lee:

- `kanon.enabled`
- `kanon.policyConfigUrl`
- `kanon.assetId`

### Paso 3. Compatibilidad legacy

Si `kanon.enabled` no viene informado, el DP mantiene compatibilidad con el comportamiento anterior:

- si existe `kanon.policyConfigUrl`, interpreta que debe anonimizar.

En ese caso deja warning indicando que esta operando por fallback antiguo.

### Paso 4. Decision principal

#### Caso A. `kanon.enabled=false`

Devuelve el dataset original.

#### Caso B. `kanon.enabled=true`

Entra en la rama de anonimización.

Dentro de esa rama:

- si falta `kanon.policyConfigUrl`, devuelve error;
- si existe, descarga dataset y policy, llama al servicio externo y devuelve el resultado anonimizado.

### Implicacion de diseño

Esto alinea el comportamiento del DP con la policy de contrato.

El Data Plane ya no decide la activacion por un parametro tecnico, sino por la señal contractual propagada desde CP.

## 5.4 KanonimizationServiceClient

Archivo: `extensions/k-anonimization-data-plane/src/main/java/org/eclipse/edc/demo/kanon/dataplane/KanonimizationServiceClient.java`

### Funcion

Encapsula toda la comunicacion HTTP con el exterior:

1. descarga el dataset original,
2. descarga la policy de anonimización,
3. construye el multipart,
4. llama al endpoint `/anonymize`,
5. devuelve el ZIP resultante.

### Observacion

No conoce policies de contrato ni `kanon.enabled`. Su trabajo empieza una vez la decision ya se ha tomado en el `DataSource`.

## 6. Comunicacion exacta CP -> DP

Este es el punto mas importante del diseño.

## 6.1 Que decide hoy el Control Plane

El CP responde a esta pregunta:

- la policy del contrato exige anonimización o no

Si la respuesta es si:

- propaga `kanon.enabled=true`

Ademas intenta propagar:

- `kanon.assetId`
- `kanon.policyConfigUrl`

## 6.2 Que decide hoy el Data Plane

El DP responde a dos preguntas distintas:

1. Debo entrar en la ruta de anonimización?
   - respuesta gobernada por `kanon.enabled`
2. Tengo la configuracion tecnica para ejecutarla?
   - respuesta gobernada por `kanon.policyConfigUrl`

Esa separacion es la mejora principal respecto al diseño anterior.

## 6.3 Que viaja en `DataFlowStartMessage`

Viajan propiedades simples, no la policy completa:

- `kanon.enabled`
- `kanon.assetId`
- `kanon.policyConfigUrl`

Esto mantiene el acoplamiento CP/DP bajo y deja al DP la ejecucion real del procesamiento.

## 7. Escenarios tecnicos actualizados

| #   | Policy `KAnonymization` | `kanon.policyConfigUrl` | Comportamiento CP            | Comportamiento DP                                      | Resultado                  |
| --- | ----------------------- | ----------------------- | ---------------------------- | ------------------------------------------------------ | -------------------------- |
| 1   | `true`                  | presente                | Propaga `kanon.enabled=true` | Anonimiza                                              | Dataset anonimizado        |
| 2   | `true`                  | ausente                 | Propaga `kanon.enabled=true` | Falla al ejecutar                                      | Error en DP                |
| 3   | `false`                 | presente                | No propaga señal activa      | Retorna original                                       | Dataset original           |
| 4   | `false`                 | ausente                 | No propaga señal activa      | Retorna original                                       | Dataset original           |
| 5   | ausente                 | presente                | No propaga señal activa      | Puede usar fallback legacy si no llega `kanon.enabled` | Compatibilidad transitoria |

## 8. Limitaciones tecnicas observables

Leyendo el codigo actual, las principales limitaciones siguen siendo estas:

1. `KanonimizationPropagationStore` es local en memoria.
2. El DP carga dataset y ZIP completos en memoria.
3. La integracion con el servicio externo depende de convenciones de nombres en el ZIP.
4. `KanonimizationAssetMetadataReader` usa reflexion y puede ocultar fallos estructurales.

## 9. Consecuencia arquitectonica del cambio reciente

Antes, la implementacion mezclaba dos planos:

- activacion contractual,
- configuracion tecnica.

Ahora esos dos planos quedan mejor separados:

- el CP marca la intencion contractual,
- el DP valida si puede ejecutarla tecnicamente.

Eso hace el sistema mas coherente con el modelo mental de EDC:

- la policy decide el comportamiento esperado del intercambio,
- el Data Plane implementa ese comportamiento sobre el contenido.

## 10. Ruta recomendada de lectura del codigo

Si vas a leer el codigo en el IDE, este sigue siendo el mejor orden:

1. `KanonimizationControlPlaneExtension`
2. `KanonimizationPolicyFunction`
3. `KanonimizationDataFlowPropertiesProvider`
4. `KanonimizationPropagationStore`
5. `KanonimizationDataPlaneExtension`
6. `KanonimizationHttpDataSourceFactory`
7. `KanonimizationHttpDataSource`
8. `KanonimizationServiceClient`

Ese orden sigue el recorrido natural del sistema: registro, decision, propagacion y ejecucion.
