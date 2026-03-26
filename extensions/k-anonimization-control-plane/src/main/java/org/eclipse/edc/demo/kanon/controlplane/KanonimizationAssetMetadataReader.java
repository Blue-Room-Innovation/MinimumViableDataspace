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

import org.eclipse.edc.spi.monitor.Monitor;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Lee metadatos del asset para obtener la URL de configuracion de politica de anonimizacion.
 * Solo busca en privateProperties: la URL de config de anonimizacion es informacion sensible
 * que no debe estar expuesta en propiedades publicas del asset.
 */
@SuppressWarnings("unchecked")
public class KanonimizationAssetMetadataReader {

    private static final String EDC_NS = "https://w3id.org/edc/v0.0.1/ns/";
    private static final String[] POLICY_URL_KEYS = {
        "kanon.policyConfigUrl",
        "edc:kanon.policyConfigUrl",
        EDC_NS + "kanon.policyConfigUrl"
    };

    private final Monitor monitor;

    public KanonimizationAssetMetadataReader(Monitor monitor) {
        this.monitor = monitor;
    }

    /**
     * Extrae la URL de configuracion de politica de anonimizacion del asset.
     * Busca SOLO en privateProperties para proteger la URL de configuracion.
     *
     * @param asset objeto Asset de EDC
     * @return URL de politica de anonimizacion (puede ser null)
     */
    public String readPolicyConfigUrl(Object asset) {
        var privateProperties = asMap(invokeNoArg(asset, "getPrivateProperties"));

        var url = firstMatching(privateProperties, POLICY_URL_KEYS);

        if (url == null || url.isBlank()) {
            monitor.warning("[K-ANON][MetadataReader] kanon.policyConfigUrl NOT FOUND in privateProperties." + 
                    " Searched keys: [kanon.policyConfigUrl, edc:kanon.policyConfigUrl, " +
                    EDC_NS + "kanon.policyConfigUrl]. Make sure the asset defines kanon.policyConfigUrl inside 'privateProperties'.");
        }

        return url;
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }

        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    // Busca la primera clave que retorne un valor no vacio
    private String firstMatching(Map<String, Object> values, String[] keys) {
        for (var key : keys) {
            var value = asString(values.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
