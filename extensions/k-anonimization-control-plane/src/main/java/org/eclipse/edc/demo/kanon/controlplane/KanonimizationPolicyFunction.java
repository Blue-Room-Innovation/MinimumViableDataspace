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

import org.eclipse.edc.connector.controlplane.contract.spi.policy.TransferProcessPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.Monitor;

/**
 * Funcion de politica que evalua el constraint KAnonymization durante la transferencia.
 * 
 * Flujo:
 * 1. Verifica que el operador sea EQ (equals)
 * 2. Si KAnonymization != true, permite transferencia sin anonimizacion
 * 3. Si KAnonymization == true, marca la transferencia para anonimizacion en Data Plane
 * 4. Intenta enriquecer la señal con assetId y policyConfigUrl cuando estan disponibles
 * 5. Data Plane decide la ejecucion final usando kanon.enabled como senal primaria
 */
public class KanonimizationPolicyFunction implements AtomicConstraintRuleFunction<Permission, TransferProcessPolicyContext> {

    public static final String K_ANONYMIZATION_CONSTRAINT_KEY = "KAnonymization";
    private final KanonimizationAssetResolver assetResolver;
    private final KanonimizationAssetMetadataReader metadataReader;
    private final KanonimizationPropagationStore propagationStore;
    private final Monitor monitor;

    public KanonimizationPolicyFunction(KanonimizationAssetResolver assetResolver,
                                        KanonimizationAssetMetadataReader metadataReader,
                                        KanonimizationPropagationStore propagationStore,
                                        Monitor monitor) {
        this.assetResolver = assetResolver;
        this.metadataReader = metadataReader;
        this.propagationStore = propagationStore;
        this.monitor = monitor;
    }
    
    @Override
    public boolean evaluate(Operator operator, Object rightOperand, Permission permission, TransferProcessPolicyContext context) {
        // 1. Valida que el operador sea EQ
        if (!Operator.EQ.equals(operator)) {
            context.reportProblem("[K-ANON] Operator '%s' not supported. Use EQ for %s".formatted(operator, K_ANONYMIZATION_CONSTRAINT_KEY));
            return false;
        }

        // 2. Si la politica NO requiere anonimizacion, permite la transferencia sin procesamiento
        if (!isTrue(rightOperand)) {
            monitor.info("[K-ANON] detection=false - policy does not require anonymization");
            return true;
        }
        
        // 3. Obtiene el assetId desde el contexto de transferencia para enriquecer la señal
        var assetId = KanonimizationPolicyContextHelper.assetIdFrom(context).orElse(null);
        String policyConfigUrl = null;

        if (assetId == null) {
            monitor.warning("[K-ANON] Cannot resolve assetId from ContractAgreement");
        } else {
            // 4. Busca el asset en el indice para obtener parametros tecnicos adicionales
            var asset = assetResolver.resolve(assetId).orElse(null);
            if (asset == null) {
                monitor.warning("[K-ANON] asset=%s not found in AssetIndex".formatted(assetId));
            } else {
                // 5. Lee la URL de configuracion de politica de anonimizacion desde metadatos del asset
                policyConfigUrl = metadataReader.readPolicyConfigUrl(asset);
            }
        }

        var agreementId = context.contractAgreement() != null ? context.contractAgreement().getId() : null;
        if (agreementId == null || agreementId.isBlank()) {
            monitor.warning("[K-ANON] detection=true but cannot store signal because agreementId is missing");
            return true;
        }

        if (policyConfigUrl == null || policyConfigUrl.isBlank()) {
            var msg = "[K-ANON] KAnonymization=true required for assetId=%s but kanon.policyConfigUrl is missing in privateProperties. Transfer BLOCKED.".formatted(assetId);
            monitor.severe(msg);
            context.reportProblem(msg);
            return false;
        }

        propagationStore.put(agreementId, new KanonimizationPropagationStore.KanonimizationSignal(assetId, policyConfigUrl, true));

        monitor.info("[K-ANON] signal stored for DP - agreementId=%s assetId=%s policyConfigUrl=%s".formatted(agreementId, assetId, policyConfigUrl));
        return true;
    }

    private boolean isTrue(Object rightOperand) {
        return rightOperand != null && "true".equals(rightOperand.toString().trim().toLowerCase());
    }
}
