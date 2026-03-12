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

import java.util.Optional;

public final class KanonimizationPolicyContextHelper {

    private KanonimizationPolicyContextHelper() {
    }

    public static Optional<String> assetIdFrom(TransferProcessPolicyContext context) {
        if (context == null || context.contractAgreement() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(context.contractAgreement().getAssetId());
    }
}
