/*
 *  Copyright (c) 2026
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 */

package org.eclipse.edc.demo.kanon.controlplane;

import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowPropertiesProvider;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcess;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.response.StatusResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Propaga la señal de anonimizacion desde Control Plane hacia Data Plane.
 * 
 * Cuando una transferencia inicia, consulta el store de propagacion usando el agreementId.
 * Si existe una señal activa, inyecta las propiedades kanon.* en el flujo de datos.
 * Estas propiedades viajan en el DataFlowStartMessage hacia Data Plane.
 */
public class KanonimizationDataFlowPropertiesProvider implements DataFlowPropertiesProvider {

    private final KanonimizationPropagationStore propagationStore;
    private final Monitor monitor;

    public KanonimizationDataFlowPropertiesProvider(KanonimizationPropagationStore propagationStore, Monitor monitor) {
        this.propagationStore = propagationStore;
        this.monitor = monitor;
    }

    /**
     * Inyecta propiedades kanon.* en el flujo de datos cuando existe señal de anonimizacion.
     * kanon.enabled es la señal primaria; el resto son parametros tecnicos opcionales.
     *
     * @param transferProcess proceso de transferencia actual
     * @param policy politica evaluada durante la transferencia
     * @return propiedades adicionales para el flujo de datos
     */
    @Override
    public StatusResult<Map<String, String>> propertiesFor(TransferProcess transferProcess, Policy policy) {
        var processId = transferProcess.getId();
        var agreementId = transferProcess.getContractId();
        
        // Obtiene y elimina la señal del store (consumo unico por transferencia)
        var signal = propagationStore.remove(agreementId).orElse(null);

        if (signal == null || !signal.enabled()) {
            return StatusResult.success(Map.of());
        }

        var properties = new HashMap<String, String>();
        properties.put("kanon.enabled", Boolean.toString(signal.enabled()));
        if (signal.assetId() != null && !signal.assetId().isBlank()) {
            properties.put("kanon.assetId", signal.assetId());
        }
        if (signal.policyConfigUrl() != null && !signal.policyConfigUrl().isBlank()) {
            properties.put("kanon.policyConfigUrl", signal.policyConfigUrl());
        }

        monitor.info("[K-ANON] processId=%s agreementId=%s propagated to DP properties: kanon.enabled=%s kanon.assetId=%s".formatted(processId, agreementId, signal.enabled(), signal.assetId()));

        return StatusResult.success(properties);
    }
}
