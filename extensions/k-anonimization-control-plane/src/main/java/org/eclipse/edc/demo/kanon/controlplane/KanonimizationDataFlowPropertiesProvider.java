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
 * Propagates CP anonymization decision to DP transfer flow properties.
 */
public class KanonimizationDataFlowPropertiesProvider implements DataFlowPropertiesProvider {

    private final KanonimizationPropagationStore propagationStore;
    private final Monitor monitor;

    public KanonimizationDataFlowPropertiesProvider(KanonimizationPropagationStore propagationStore, Monitor monitor) {
        this.propagationStore = propagationStore;
        this.monitor = monitor;
    }

    // Adds kanon.* properties when a positive anonymization signal exists for the agreement.
    @Override
    public StatusResult<Map<String, String>> propertiesFor(TransferProcess transferProcess, Policy policy) {
        var processId = transferProcess.getId();
        var agreementId = transferProcess.getContractId();
        var signal = propagationStore.remove(agreementId).orElse(null);

        if (signal == null || !signal.enabled()) {
            return StatusResult.success(Map.of());
        }

        var properties = new HashMap<String, String>();
        properties.put("kanon.enabled", Boolean.toString(signal.enabled()));
        properties.put("kanon.assetId", signal.assetId());
        properties.put("kanon.policyConfigUrl", signal.policyConfigUrl());

        monitor.info("[K-ANON] processId=%s agreementId=%s propagated to DP properties: kanon.enabled=%s kanon.assetId=%s"
                .formatted(processId, agreementId, signal.enabled(), signal.assetId()));

        return StatusResult.success(properties);
    }
}
