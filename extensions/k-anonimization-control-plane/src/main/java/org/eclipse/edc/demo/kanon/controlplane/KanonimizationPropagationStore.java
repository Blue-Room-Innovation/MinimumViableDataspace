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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class KanonimizationPropagationStore {

    private final Map<String, KanonimizationSignal> byAgreementId = new ConcurrentHashMap<>();

    public void put(String agreementId, KanonimizationSignal signal) {
        if (agreementId == null || agreementId.isBlank() || signal == null) {
            return;
        }
        byAgreementId.put(agreementId, signal);
    }

    public Optional<KanonimizationSignal> remove(String agreementId) {
        if (agreementId == null || agreementId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byAgreementId.remove(agreementId));
    }

    public record KanonimizationSignal(String assetId, String policyConfigUrl, boolean enabled) {
    }
}
