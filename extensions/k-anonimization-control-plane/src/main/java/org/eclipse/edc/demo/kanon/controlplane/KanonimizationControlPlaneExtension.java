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

import org.eclipse.edc.connector.controlplane.asset.spi.index.AssetIndex;
import org.eclipse.edc.connector.controlplane.contract.spi.policy.TransferProcessPolicyContext;
import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowPropertiesProvider;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.engine.spi.RuleBindingRegistry;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.eclipse.edc.policy.model.OdrlNamespace.ODRL_SCHEMA;

@Extension(value = KanonimizationControlPlaneExtension.NAME)
public class KanonimizationControlPlaneExtension implements ServiceExtension {

    public static final String NAME = "K-Anonimization Control Plane Extension";

    @Inject
    private PolicyEngine policyEngine;

    @Inject
    private RuleBindingRegistry ruleBindingRegistry;

    @Inject
    private AssetIndex assetIndex;

    @Inject
    private Monitor monitor;

    private KanonimizationPropagationStore propagationStore;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        propagationStore = new KanonimizationPropagationStore();
        var assetResolver = new KanonimizationAssetResolver(assetIndex);
        var metadataReader = new KanonimizationAssetMetadataReader();
        var function = new KanonimizationPolicyFunction(assetResolver, metadataReader, propagationStore, monitor.withPrefix("K-ANON"));

        ruleBindingRegistry.bind("use", TransferProcessPolicyContext.TRANSFER_SCOPE);
        ruleBindingRegistry.bind(ODRL_SCHEMA + "use", TransferProcessPolicyContext.TRANSFER_SCOPE);
        ruleBindingRegistry.bind(KanonimizationPolicyFunction.K_ANONYMIZATION_CONSTRAINT_KEY, TransferProcessPolicyContext.TRANSFER_SCOPE);

        policyEngine.registerFunction(
                TransferProcessPolicyContext.class,
                Permission.class,
                KanonimizationPolicyFunction.K_ANONYMIZATION_CONSTRAINT_KEY,
                function
        );

        monitor.info("[K-ANON] Registered policy function for leftOperand='%s' on scope '%s'."
                .formatted(KanonimizationPolicyFunction.K_ANONYMIZATION_CONSTRAINT_KEY, TransferProcessPolicyContext.TRANSFER_SCOPE));
    }

    @Provider
    public DataFlowPropertiesProvider kanonimizationDataFlowPropertiesProvider() {
        return new KanonimizationDataFlowPropertiesProvider(propagationStore, monitor.withPrefix("K-ANON"));
    }
}
