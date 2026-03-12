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

import java.util.Optional;

public class KanonimizationAssetResolver {

    private final AssetIndex assetIndex;

    public KanonimizationAssetResolver(AssetIndex assetIndex) {
        this.assetIndex = assetIndex;
    }

    public Optional<Object> resolve(String assetId) {
        return Optional.ofNullable(assetIndex.findById(assetId));
    }
}
