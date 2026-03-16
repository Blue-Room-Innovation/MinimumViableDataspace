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

/**
 * Almacena señales de anonimizacion indexadas por agreementId para propagacion CP -> DP.
 * 
 * Lifecycle:
 * 1. KanonimizationPolicyFunction almacena señal cuando detecta KAnonymization=true
 * 2. KanonimizationDataFlowPropertiesProvider consume señal al iniciar transferencia
 * 
 * Store en memoria (no distribuido): adecuado para PoC, no para produccion distribuida.
 */
public class KanonimizationPropagationStore {

    private final Map<String, KanonimizationSignal> byAgreementId = new ConcurrentHashMap<>();

    /**
     * Almacena una señal de anonimizacion para un agreementId especifico.
     */
    public void put(String agreementId, KanonimizationSignal signal) {
        if (agreementId == null || agreementId.isBlank() || signal == null) {
            return;
        }
        byAgreementId.put(agreementId, signal);
    }

    /**
     * Obtiene y elimina la señal de anonimizacion para un agreementId (consumo unico).
     */
    public Optional<KanonimizationSignal> remove(String agreementId) {
        if (agreementId == null || agreementId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byAgreementId.remove(agreementId));
    }

    /**
     * Señal de anonimizacion que viaja de CP a DP.

     * @param assetId ID del asset a anonimizar
     * @param policyConfigUrl URL de configuracion de politica de anonimizacion
     * @param enabled flag booleano de activacion (siempre true cuando existe la señal)
     */
    public record KanonimizationSignal(String assetId, String policyConfigUrl, boolean enabled) {}
}
