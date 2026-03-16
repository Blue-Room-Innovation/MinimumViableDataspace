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
 * 3. Si KAnonymization == true, busca el asset y su policyConfigUrl
 * 4. Si falta policyConfigUrl, RECHAZA la transferencia (fail-fast)
 * 5. Si todo es valido, almacena señal para propagacion a Data Plane
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
        
        // 3. Obtiene el assetId desde el contexto de transferencia
        var assetId = KanonimizationPolicyContextHelper.assetIdFrom(context).orElse(null);
        if (assetId == null) {
            monitor.warning("[K-ANON] Cannot resolve assetId from ContractAgreement");
            return true;
        }
        
        // 4. Busca el asset en el indice
        var asset = assetResolver.resolve(assetId).orElse(null);
        if (asset == null) {
            monitor.warning("[K-ANON] asset=%s not found in AssetIndex".formatted(assetId));
            return true;
        }

        // 5. Lee la URL de configuracion de politica de anonimizacion desde metadatos del asset
        var policyConfigUrl = metadataReader.readPolicyConfigUrl(asset);

        // 6. VALIDACION CRITICA: Si se requiere anonimizacion pero falta policyConfigUrl, RECHAZA la transferencia
        if (policyConfigUrl == null || policyConfigUrl.isBlank()) {
            var errorMsg = "[K-ANON] asset=%s requires anonymization but kanon.policyConfigUrl is missing. Transfer denied.".formatted(assetId);
            monitor.severe(errorMsg);
            context.reportProblem(errorMsg);
            return false; // Fail-fast: rechaza transferencia con configuracion incompleta
        }

        // 7. Configuracion valida: registra deteccion y prepara señal para Data Plane
        monitor.info("[K-ANON] detection=true asset=%s policyConfigUrl=%s".formatted(assetId, policyConfigUrl));

        var agreementId = context.contractAgreement() != null ? context.contractAgreement().getId() : null;
        propagationStore.put(agreementId, new KanonimizationPropagationStore.KanonimizationSignal(assetId, policyConfigUrl, true));

        monitor.info("[K-ANON] signal stored for DP - agreementId=%s assetId=%s".formatted(agreementId, assetId));
        return true;
    }

    private boolean isTrue(Object rightOperand) {
        return rightOperand != null && "true".equals(rightOperand.toString().trim().toLowerCase());
    }
}
