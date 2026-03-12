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
import org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.types.domain.DataAddress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult.error;
import static org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult.success;

/**
 * DataSource that conditionally anonymizes HttpData content before delivery.
 */
public class KanonimizationHttpDataSource implements DataSource {

    private static final Pattern FALLBACK_ANON_DATASET_PATTERN = Pattern.compile("dataset_anonymized(?:_.*)?\\..+");

    private final KanonimizationServiceClient client;
    private final DataAddress sourceDataAddress;
    private final String requestId;
    private final String processId;
    private final String agreementId;
    private final String flowAssetId;
    private final Map<String, String> flowProperties;
    private final Monitor monitor;

    public KanonimizationHttpDataSource(KanonimizationServiceClient client,
                                        DataAddress sourceDataAddress,
                                        String requestId,
                                        String processId,
                                        String agreementId,
                                        String flowAssetId,
                                        Map<String, String> flowProperties,
                                        Monitor monitor) {
        this.client = client;
        this.sourceDataAddress = sourceDataAddress;
        this.requestId = requestId;
        this.processId = processId;
        this.agreementId = agreementId;
        this.flowAssetId = flowAssetId;
        this.flowProperties = flowProperties;
        this.monitor = monitor;
    }

    // Decides anonymization=true/false, optionally calls external API, and returns output stream part.
    @Override
    public StreamResult<Stream<Part>> openPartStream() {
        try {
            var datasetUrl = asString(sourceDataAddress.getProperty("baseUrl"));
            if (datasetUrl == null || datasetUrl.isBlank()) {
                return error("[K-ANON][DP] Missing baseUrl for request " + requestId);
            }

            var propagatedPolicyUrl = firstNonBlank(
                    flowProperties.get("kanon.policyConfigUrl"),
                    asString(sourceDataAddress.getProperty("kanon.policyConfigUrl"))
            );
            var propagatedAssetId = firstNonBlank(flowProperties.get("kanon.assetId"), flowAssetId);
            var enabledFlag = firstNonBlank(
                    flowProperties.get("kanon.enabled"),
                    asString(sourceDataAddress.getProperty("kanon.enabled")),
                    asString(sourceDataAddress.getProperty("kAnonimizacion"))
            );

            var anonymizationRequested = isTruthy(enabledFlag) || (propagatedPolicyUrl != null && !propagatedPolicyUrl.isBlank());
            if (!anonymizationRequested) {
                monitor.info("[K-ANON][DP] detection=false requestId=%s processId=%s agreementId=%s assetId=%s"
                        .formatted(requestId, processId, agreementId, propagatedAssetId));
                var original = client.downloadFile(datasetUrl);
                var mediaType = mediaTypeFromUrl(datasetUrl);
                var part = new InMemoryPart(fileNameFromUrl(datasetUrl), original, mediaType);
                return success(Stream.of(part));
            }

            if (propagatedPolicyUrl == null || propagatedPolicyUrl.isBlank()) {
                return error("[K-ANON][DP] requestId=%s processId=%s agreementId=%s assetId=%s requires anonymization but kanon.policyConfigUrl is missing."
                        .formatted(requestId, processId, agreementId, propagatedAssetId));
            }

            var datasetFormat = datasetFormatFromUrl(datasetUrl);
            monitor.info("[K-ANON][DP] detection=true requestId=%s processId=%s agreementId=%s assetId=%s"
                    .formatted(requestId, processId, agreementId, propagatedAssetId));

            var zipBytes = client.anonymizeFromUrls(datasetUrl, propagatedPolicyUrl, datasetFormat);
            var anonymizedFile = extractAnonymizedDataset(zipBytes, datasetFormat);
            var part = new InMemoryPart(anonymizedFile.fileName(), anonymizedFile.content(), mediaTypeFromUrl(anonymizedFile.fileName()));
            return success(Stream.of(part));
        } catch (Exception e) {
            return error("[K-ANON][DP] requestId=%s processId=%s agreementId=%s failed: %s"
                    .formatted(requestId, processId, agreementId, e.getMessage()));
        }
    }

    // No persistent resources to release.
    @Override
    public void close() {
        // no-op
    }

    /**
     * Extracts anonymized dataset from zip response, prioritizing expected filename by format.
     */
    private ExtractedFile extractAnonymizedDataset(byte[] zipBytes, String datasetFormat) {
        ExtractedFile fallback = null;
        ExtractedFile preferred = null;
        var seenEntries = new StringBuilder();
        var preferredFileName = preferredAnonymizedFileName(datasetFormat);

        try (var zipInputStream = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                var entryName = entry.getName();
                if (seenEntries.length() > 0) {
                    seenEntries.append(", ");
                }
                seenEntries.append(entryName);

                var fileName = normalizeFileName(entryName);
                var content = readAllBytes(zipInputStream);

                if (preferredFileName.equalsIgnoreCase(fileName)) {
                    preferred = new ExtractedFile(fileName, content);
                    break;
                }

                if (fallback == null && FALLBACK_ANON_DATASET_PATTERN.matcher(fileName).matches()) {
                    fallback = new ExtractedFile(fileName, content);
                }
            }

            if (preferred != null) {
                return preferred;
            }

            if (fallback != null) {
                return fallback;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to unzip anonymization response: " + e.getMessage(), e);
        }

        throw new RuntimeException("Anonymization response zip does not contain expected '%s' nor dataset_anonymized* fallback. Entries found: [%s]"
                .formatted(preferredFileName, seenEntries));
    }

    private String preferredAnonymizedFileName(String datasetFormat) {
        if ("json".equalsIgnoreCase(datasetFormat)) {
            return "dataset_anonymized.json";
        }
        if ("excel".equalsIgnoreCase(datasetFormat)) {
            return "dataset_anonymized.xlsx";
        }
        return "dataset_anonymized.csv";
    }

    private String normalizeFileName(String entryName) {
        var slash = entryName.lastIndexOf('/');
        var backslash = entryName.lastIndexOf('\\');
        var index = Math.max(slash, backslash);
        if (index >= 0 && index + 1 < entryName.length()) {
            return entryName.substring(index + 1);
        }
        return entryName;
    }

    /**
     * Reads all bytes from the current zip entry stream.
     */
    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        var out = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Detects logical dataset format from URL extension.
     */
    private String datasetFormatFromUrl(String datasetUrl) {
        var lower = datasetUrl.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return "csv";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return "excel";
        }
        return "binary";
    }

    /**
     * Detects media type from file extension.
     */
    private String mediaTypeFromUrl(String value) {
        var lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (lower.endsWith(".xls")) {
            return "application/vnd.ms-excel";
        }
        return "application/octet-stream";
    }

    /**
     * Extracts filename from URL.
     */
    private String fileNameFromUrl(String url) {
        var lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 1 < url.length()) {
            return url.substring(lastSlash + 1);
        }
        return "dataset";
    }

    /**
     * Returns first non-blank value.
     */
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    /**
     * Returns first non-blank across three candidates.
     */
    private String firstNonBlank(String first, String second, String third) {
        return firstNonBlank(firstNonBlank(first, second), third);
    }

    /**
     * Converts common true-like strings to boolean.
     */
    private boolean isTruthy(String value) {
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    /**
     * Safe object to string conversion.
     */
    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private record ExtractedFile(String fileName, byte[] content) {
    }

    private record InMemoryPart(String name, byte[] content, String mediaType) implements Part {
        @Override
        public long size() {
            return content.length;
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public String mediaType() {
            return mediaType;
        }
    }
}
