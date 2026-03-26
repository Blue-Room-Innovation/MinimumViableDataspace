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

    private static final String EDC_NAMESPACE = "https://w3id.org/edc/v0.0.1/ns/";
    private static final String KANON_ENABLED = "kanon.enabled";
    private static final String KANON_ENABLED_NS = EDC_NAMESPACE + KANON_ENABLED;
    private static final String KANON_REQUIRED = "kanon.anonymization.required";
    private static final String KANON_REQUIRED_NS = EDC_NAMESPACE + KANON_REQUIRED;
    private static final String KANON_ASSET_ID = "kanon.assetId";
    private static final String KANON_ASSET_ID_NS = EDC_NAMESPACE + KANON_ASSET_ID;
    private static final String KANON_POLICY_CONFIG_URL = "kanon.policyConfigUrl";
    private static final String KANON_POLICY_CONFIG_URL_NS = EDC_NAMESPACE + KANON_POLICY_CONFIG_URL;

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
        var enabledValue = Boolean.toString(signal.enabled());
        properties.put(KANON_ENABLED, enabledValue);
        properties.put(KANON_ENABLED_NS, enabledValue);
        properties.put(KANON_REQUIRED, enabledValue);
        properties.put(KANON_REQUIRED_NS, enabledValue);
        if (signal.assetId() != null && !signal.assetId().isBlank()) {
            properties.put(KANON_ASSET_ID, signal.assetId());
            properties.put(KANON_ASSET_ID_NS, signal.assetId());
        }
        if (signal.policyConfigUrl() != null && !signal.policyConfigUrl().isBlank()) {
            properties.put(KANON_POLICY_CONFIG_URL, signal.policyConfigUrl());
            properties.put(KANON_POLICY_CONFIG_URL_NS, signal.policyConfigUrl());
        }

        return StatusResult.success(properties);
    }
}
