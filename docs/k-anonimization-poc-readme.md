# K-Anonimization en MVD/EDC

Este documento describe la PoC de k-anonimizacion desde una perspectiva funcional y de caso de uso.

La idea es explicar:

- que problema se ha identificado,
- como se expresa ese requisito en la policy del contrato,
- que configuracion minima necesita el asset,
- que comportamiento funcional cabe esperar en una transferencia,
- y cuando conviene pasar al documento tecnico detallado.

Para el detalle clase por clase y el flujo interno exacto de Control Plane y Data Plane, consultar [docs/k-anonimization-extensions-deep-dive.md](docs/k-anonimization-extensions-deep-dive.md).

## 1. Caso de uso identificado

Se ha identificado un caso de uso en el que un proveedor quiere compartir un dataset, pero el intercambio debe exigir un procesamiento previo de anonimizacion antes de entregar los datos al consumidor.

Ejemplo conceptual:

- el proveedor publica un dataset potencialmente sensible,
- el contrato indica que ese dataset solo puede transferirse bajo condicion de anonimización,
- el conector EDC detecta esa condicion,
- y el Data Plane entrega al consumidor el resultado anonimizado en lugar del dataset bruto.

La separacion funcional es esta:

- Control Plane: interpreta la policy del contrato y marca la transferencia.
- Data Plane: ejecuta la transformacion sobre el contenido que se entrega.

## 2. Criterio minimo de activacion

La PoC se activa con dos piezas de informacion:

1. La policy del contrato expresa la exigencia de anonimización.
2. El asset aporta la URL de la policy tecnica de anonimización.

### 2.1 Policy del contrato

La policy marca la obligacion de anonimizar mediante un constraint:

- `leftOperand = KAnonymization`
- `operator = eq`
- `rightOperand = true`

Interpretacion funcional:

- si `KAnonymization=true`, la transferencia queda marcada para anonimizacion;
- si no existe ese constraint, o el valor no es `true`, el flujo continua sin anonimizacion.

### 2.2 Metadato tecnico del asset

El asset debe exponer:

- `kanon.policyConfigUrl`

Ese valor apunta al recurso tecnico que el Data Plane necesita para ejecutar la anonimización real.

## 3. Regla funcional actual

La regla funcional es la siguiente:

1. La policy de contrato activa la señal principal de anonimización en el Control Plane.
2. Esa señal viaja al Data Plane como `kanon.enabled=true`.
3. El Data Plane usa `kanon.enabled` como fuente de verdad para decidir si entra en la rama de anonimización.
4. `kanon.policyConfigUrl` es un parametro tecnico adicional que el DP necesita para ejecutar la anonimización.
5. Si `kanon.enabled=true` pero falta `kanon.policyConfigUrl`, el fallo ocurre en el **Data Plane** al intentar ejecutar la anonimización.

En una frase:

- la policy decide la intencion contractual (señal: `kanon.enabled`),
- la URL de policy decide si esa intencion se puede ejecutar correctamente (soporte: `kanon.policyConfigUrl`).

## 4. Comportamiento esperado

### 4.1 Escenario principal

Si el contrato exige anonimización y el asset tiene `kanon.policyConfigUrl`:

- la transferencia queda marcada como anonimizable,
- el Data Plane ejecuta la llamada al servicio externo,
- y el consumidor recibe el dataset anonimizado.

### 4.2 Escenario sin anonimización

Si el contrato no exige `KAnonymization=true`:

- el flujo no activa la rama de anonimización,
- y el consumidor recibe el dataset original.

### 4.3 Escenario con configuracion incompleta

Si el contrato exige `KAnonymization=true` pero el asset no tiene `kanon.policyConfigUrl`:

- el Control Plane propaga `kanon.enabled=true` sin rechazar la transferencia,
- el Data Plane entra en la rama de anonimización,
- pero falla porque no dispone de la configuracion tecnica necesaria.

Esto deja clara la diferencia entre:

- señal contractual: `kanon.enabled`
- configuracion operativa: `kanon.policyConfigUrl`

## 5. Flujo funcional end-to-end

El flujo funcional, sin entrar en detalle de clases, es el siguiente:

1. El proveedor publica un asset HTTP.
2. El proveedor asocia una policy de contrato que exige `KAnonymization=true`.
3. El consumidor negocia contrato y solicita transferencia.
4. El Control Plane detecta que esa transferencia debe anonimizarse.
5. El Control Plane propaga la señal `kanon.enabled=true` al flujo hacia Data Plane.
6. El Data Plane recibe la transferencia.
7. Si la transferencia esta marcada y tiene la configuracion tecnica necesaria, ejecuta la anonimización.
8. El consumidor recibe el resultado final.

## 6. Ejemplo minimo de policy

Ejemplo de policy de contrato:

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

## 7. Ejemplo minimo de asset

Ejemplo simplificado de asset HTTP con la URL de policy tecnica:

```json
{
  "@context": ["https://w3id.org/edc/connector/management/v0.0.1"],
  "@id": "asset-kanon-demo-1",
  "@type": "Asset",
  "properties": {
    "edc:name": "KAnon demo asset",
    "edc:contenttype": "application/csv"
  },
  "dataAddress": {
    "type": "HttpData",
    "baseUrl": "http://host.docker.internal:8088/medical_example.csv",
    "kanon.policyConfigUrl": "http://host.docker.internal:8088/medical_example_policy.json"
  }
}
```

## 8. Matriz funcional de escenarios

| #   | Policy `KAnonymization` | Asset `kanon.policyConfigUrl` | Comportamiento CP            | Comportamiento DP              | Resultado funcional                |
| --- | ----------------------- | ----------------------------- | ---------------------------- | ------------------------------ | ---------------------------------- |
| 1   | `true`                  | presente                      | Propaga `kanon.enabled=true` | Ejecuta anonimización          | Se ejecuta anonimización           |
| 2   | `true`                  | ausente                       | Propaga `kanon.enabled=true` | **Falla** al intentar ejecutar | El DP falla al intentar anonimizar |
| 3   | `false`                 | presente                      | No propaga señal activa      | Retorna dataset original       | Se entrega dataset original        |
| 4   | `false`                 | ausente                       | No propaga señal activa      | Retorna dataset original       | Se entrega dataset original        |
| 5   | ausente                 | cualquiera                    | Funcion no invocada          | Retorna dataset original       | Se entrega dataset original        |

**Nota sobre el escenario #2:** cuando la policy exige anonimización pero falta `kanon.policyConfigUrl`, el Control Plane no rechaza la transferencia. La transferencia avanza con `kanon.enabled=true` y es el Data Plane quien detecta la configuracion incompleta y falla al intentar ejecutar la anonimización.

## 9. Modos de transferencia HTTP

La PoC sigue siendo compatible con los modos HTTP PULL y HTTP PUSH de EDC.

### 9.1 PULL

En modo PULL:

- el consumidor descarga desde el endpoint expuesto por el proveedor,
- y el Data Plane del proveedor entrega el dataset ya anonimizado o el original segun corresponda.

### 9.2 PUSH

En modo PUSH:

- el consumidor expone un endpoint receptor,
- y el Data Plane del proveedor empuja al endpoint del consumidor el resultado del flujo.

La ruta de anonimización no depende del modo PULL o PUSH; depende de la señal de policy y de la configuracion tecnica disponible.

## 10. Dependencias funcionales de la PoC

Para que el flujo funcione, se necesita:

1. Una policy de contrato con `KAnonymization=true`.
2. Un asset `HttpData` con `baseUrl`.
3. Un `kanon.policyConfigUrl` accesible cuando se quiera ejecutar anonimización real.
4. Un Data Plane con el servicio de anonimización configurado.

Configuracion relevante del DP:

- `edc.anonymization.enabled=true`
- `edc.anonymization.service.url=...`
- opcionalmente `edc.anonymization.service.apiKeyId`
- opcionalmente `edc.anonymization.service.apiKeySecret`
- `edc.anonymization.timeout.seconds=...`

## 11. Limitaciones que conviene conocer

Aunque este documento es funcional, hay varias limitaciones practicas que afectan al uso de la PoC:

1. La propagacion entre CP y DP esta pensada como PoC, no como solucion distribuida endurecida.
2. El procesamiento real depende de un servicio externo de anonimización.
3. El soporte actual esta centrado en `HttpData`.
4. El Data Plane carga el contenido completo en memoria en esta implementacion.

## 12. Cuando leer el deep dive tecnico

Este README es suficiente si quieres entender:

- el caso de uso,
- la idea funcional,
- la policy minima,
- el ejemplo de asset,
- y el comportamiento observable.

Debes pasar a [docs/k-anonimization-extensions-deep-dive.md](docs/k-anonimization-extensions-deep-dive.md) si quieres entender:

- que clases Java intervienen,
- que hace cada metodo,
- como se comunica exactamente CP -> DP,
- como se usa `kanon.enabled`,
- y donde se ejecuta la llamada al servicio externo.
