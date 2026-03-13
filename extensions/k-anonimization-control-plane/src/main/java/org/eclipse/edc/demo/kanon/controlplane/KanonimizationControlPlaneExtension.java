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
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.engine.spi.RuleBindingRegistry;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.util.Objects;

import static org.eclipse.edc.policy.model.OdrlNamespace.ODRL_SCHEMA;

/**
 * Minimal CP extension: binds/registers KAnonymization so policy definitions are accepted,
 * without controlling DP anonymization behavior.
 */
@Extension(value = KanonimizationControlPlaneExtension.NAME)
public class KanonimizationControlPlaneExtension implements ServiceExtension {

    public static final String NAME = "K-Anonimization Control Plane Binding Extension";
    public static final String K_ANONYMIZATION_CONSTRAINT_KEY = "KAnonymization";

    @Inject
    private PolicyEngine policyEngine;

    @Inject
    private RuleBindingRegistry ruleBindingRegistry;

    @Inject
    private Monitor monitor;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        ruleBindingRegistry.bind("use", TransferProcessPolicyContext.TRANSFER_SCOPE);
        ruleBindingRegistry.bind(ODRL_SCHEMA + "use", TransferProcessPolicyContext.TRANSFER_SCOPE);
        ruleBindingRegistry.bind(K_ANONYMIZATION_CONSTRAINT_KEY, TransferProcessPolicyContext.TRANSFER_SCOPE);

        policyEngine.registerFunction(
                TransferProcessPolicyContext.class,
                Permission.class,
                K_ANONYMIZATION_CONSTRAINT_KEY,
                new NoOpkAnonymizationPolicyFunction()
        );

        monitor.info("[K-ANON][CP] Registered minimal binding/function for leftOperand='%s' on scope '%s'."
                .formatted(K_ANONYMIZATION_CONSTRAINT_KEY, TransferProcessPolicyContext.TRANSFER_SCOPE));
    }

    private static class NoOpkAnonymizationPolicyFunction implements AtomicConstraintRuleFunction<Permission, TransferProcessPolicyContext> {

        @Override
        public boolean evaluate(Operator operator, Object rightOperand, Permission permission, TransferProcessPolicyContext context) {
            if (!Operator.EQ.equals(operator)) {
                context.reportProblem("[K-ANON][CP] Unsupported operator '%s'. Only '%s' is supported for %s"
                        .formatted(operator, Operator.EQ, K_ANONYMIZATION_CONSTRAINT_KEY));
                return false;
            }

            return isTrue(rightOperand);
        }

        private boolean isTrue(Object rightOperand) {
            if (rightOperand == null) {
                return false;
            }
            return Objects.equals("true", rightOperand.toString().trim().toLowerCase());
        }
    }
}
