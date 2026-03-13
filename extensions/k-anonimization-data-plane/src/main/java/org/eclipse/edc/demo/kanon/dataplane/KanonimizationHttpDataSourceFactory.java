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

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSourceFactory;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.jetbrains.annotations.NotNull;

public class KanonimizationHttpDataSourceFactory implements DataSourceFactory {

    private final KanonimizationServiceClient client;
    private final Monitor monitor;

    public KanonimizationHttpDataSourceFactory(KanonimizationServiceClient client, Monitor monitor) {
        this.client = client;
        this.monitor = monitor;
    }

    @Override
    public String supportedType() {
        return "HttpData";
    }

    @Override
    public DataSource createSource(DataFlowStartMessage request) {
        return new KanonimizationHttpDataSource(
                client,
                request.getSourceDataAddress(),
                request.getId(),
                request.getProcessId(),
                request.getAgreementId(),
                request.getAssetId(),
                monitor
        );
    }

    @Override
    public @NotNull Result<Void> validateRequest(DataFlowStartMessage request) {
        var baseUrl = asString(request.getSourceDataAddress().getProperty("baseUrl"));
        if (baseUrl == null || baseUrl.isBlank()) {
            return Result.failure("HttpData source must define 'baseUrl'.");
        }
        return Result.success();
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }
}
