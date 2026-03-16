/*
 *  Copyright (c) 2026
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 */

package org.eclipse.edc.demo.kanon.dataplane;

import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

/**
 * Registers the anonymization DataSourceFactory in DP when runtime settings enable it.
 */
@Extension(value = KanonimizationDataPlaneExtension.NAME)
public class KanonimizationDataPlaneExtension implements ServiceExtension {

    public static final String NAME = "K-Anonimization Data Plane Extension";

    @Setting(key = "edc.anonymization.enabled", defaultValue = "false", description = "Enables anonymization processing in dataplane for HttpData sources.")
    private boolean anonymizationEnabled;

    @Setting(key = "edc.anonymization.service.url", required = false, description = "Base URL of anonymization service.")
    private String anonymizationServiceUrl;

    @Setting(key = "edc.anonymization.service.apiKeyId", required = false, defaultValue = "", description = "API key id for anonymization service.")
    private String anonymizationServiceApiKeyId;

    @Setting(key = "edc.anonymization.service.apiKeySecret", required = false, defaultValue = "", description = "API key secret for anonymization service.")
    private String anonymizationServiceApiKeySecret;

    @Setting(key = "edc.anonymization.timeout.seconds", defaultValue = "300", description = "Timeout for download and anonymization service requests.")
    private long timeoutSeconds;

    @Inject
    private Monitor monitor;

    @Inject
    private PipelineService pipelineService;

    @Override
    public String name() {
        return NAME;
    }

    // Reads anonymization settings and wires the DP client/factory.
    @Override
    public void initialize(ServiceExtensionContext context) {
        var prefixedMonitor = monitor.withPrefix("K-ANON");
        var config = context.getConfig();

        var effectiveApiKeyId = firstNonBlank(
                anonymizationServiceApiKeyId,
                config.getString("edc.anonymization.service.apikeyid", null)
        );

        var effectiveApiKeySecret = firstNonBlank(
                anonymizationServiceApiKeySecret,
                config.getString("edc.anonymization.service.apikeysecret", null)
        );

        if (!anonymizationEnabled) {
            return;
        }

        if (anonymizationServiceUrl == null || anonymizationServiceUrl.isBlank()) {
            prefixedMonitor.warning("[DP] Anonymization is enabled but edc.anonymization.service.url is missing. Factory not registered.");
            return;
        }

        var client = createClient(effectiveApiKeyId, effectiveApiKeySecret, prefixedMonitor);

        pipelineService.registerFactory(new KanonimizationHttpDataSourceFactory(client, prefixedMonitor));
    }

    /**
     * Returns the first non-blank candidate from four input values.
     */
    private String firstNonBlank(String first, String second, String third, String fourth) {
        return firstNonBlank(firstNonBlank(first, second), firstNonBlank(third, fourth));
    }

    /**
     * Creates the client used for dataset/policy download and anonymization endpoint invocation.
     */
    private KanonimizationServiceClient createClient(String apiKeyId, String apiKeySecret, Monitor prefixedMonitor) {
        return new KanonimizationServiceClient(anonymizationServiceUrl, timeoutSeconds, apiKeyId, apiKeySecret, prefixedMonitor);
    }

    /**
     * Returns first non-blank value or empty string when both are blank/null.
     */
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return "";
    }

}
