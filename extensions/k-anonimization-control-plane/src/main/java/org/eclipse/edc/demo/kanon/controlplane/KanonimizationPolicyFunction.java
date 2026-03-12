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

import java.util.Objects;

/**
 * Evaluates the custom KAnonymization policy constraint during transfer policy evaluation.
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

    // Resolves asset metadata, decides anonymization=true/false, and stores propagation signal for DP.
    @Override
    public boolean evaluate(Operator operator, Object rightOperand, Permission permission, TransferProcessPolicyContext context) {
        if (!Operator.EQ.equals(operator)) {
            context.reportProblem("[K-ANON] Unsupported operator '%s'. Only '%s' is supported for %s"
                    .formatted(operator, Operator.EQ, K_ANONYMIZATION_CONSTRAINT_KEY));
            return false;
        }

        if (!isTrue(rightOperand)) {
            monitor.info("[K-ANON] detection=false policy does not request KAnonymization=true.");
            return true;
        }

        var assetId = KanonimizationPolicyContextHelper.assetIdFrom(context)
                .orElse(null);

        if (assetId == null) {
            monitor.warning("[K-ANON] Could not resolve assetId from ContractAgreement in TransferProcessPolicyContext.");
            return true;
        }

        var asset = assetResolver.resolve(assetId).orElse(null);
        if (asset == null) {
            monitor.warning("[K-ANON] asset=%s was not found in AssetIndex while evaluating KAnonymization constraint.".formatted(assetId));
            return true;
        }

        var snapshot = metadataReader.read(asset);
        if (!snapshot.kanonimizacionEnabled()) {
            monitor.warning("[K-ANON] asset=%s has policy KAnonymization=true but asset metadata kAnonimizacion!=true.".formatted(assetId));
            return true;
        }

        if (snapshot.policyConfigUrl() == null || snapshot.policyConfigUrl().isBlank()) {
            monitor.warning("[K-ANON] asset=%s has kAnonimizacion=true but kanon.policyConfigUrl is missing.".formatted(assetId));
            return true;
        }

        monitor.info("[K-ANON] detection=true asset=%s policyConfigUrl=%s"
                .formatted(assetId, snapshot.policyConfigUrl()));

        var agreementId = context.contractAgreement() != null ? context.contractAgreement().getId() : null;
        propagationStore.put(
                agreementId,
                new KanonimizationPropagationStore.KanonimizationSignal(assetId, snapshot.policyConfigUrl(), true)
        );
        monitor.info("[K-ANON] propagation-ready agreementId=%s kanon.enabled=true kanon.assetId=%s"
                .formatted(agreementId, assetId));
        return true;
    }

    /**
     * Converts right operand to boolean-like truth check.
     */
    private boolean isTrue(Object rightOperand) {
        if (rightOperand == null) {
            return false;
        }
        return Objects.equals("true", rightOperand.toString().trim().toLowerCase());
    }
}
