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

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Lee metadatos del asset para obtener la URL de configuracion de politica de anonimizacion.
 * Busca en properties, privateProperties y dataAddress del asset.
 */
@SuppressWarnings("unchecked")
public class KanonimizationAssetMetadataReader {

    private static final String EDC_NS = "https://w3id.org/edc/v0.0.1/ns/";
    private static final String[] POLICY_URL_KEYS = {
        "kanon.policyConfigUrl",
        "edc:kanon.policyConfigUrl",
        EDC_NS + "kanon.policyConfigUrl"
    };

    /**
     * Extrae la URL de configuracion de politica de anonimizacion del asset.
     *
     * @param asset objeto Asset de EDC
     * @return URL de politica de anonimizacion (puede ser null)
     */
    public String readPolicyConfigUrl(Object asset) {
        var properties = asMap(invokeNoArg(asset, "getProperties"));
        var privateProperties = asMap(invokeNoArg(asset, "getPrivateProperties"));
        var dataAddress = invokeNoArg(asset, "getDataAddress");
        var dataAddressProperties = asMap(invokeNoArg(dataAddress, "getProperties"));

        // Busca en todos los posibles lugares donde puede estar la URL de politica
        return firstNonBlank(
                firstMatching(properties, POLICY_URL_KEYS),
                firstMatching(privateProperties, POLICY_URL_KEYS),
                firstMatching(dataAddressProperties, POLICY_URL_KEYS)
        );
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

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private String firstNonBlank(String first, String second, String third) {
        return firstNonBlank(firstNonBlank(first, second), third);
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
