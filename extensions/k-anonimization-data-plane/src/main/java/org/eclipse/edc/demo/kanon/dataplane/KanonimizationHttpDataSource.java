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
 * DataSource que decide si anonimizar el contenido HTTP antes de entregarlo al consumidor.
 * 
 * Flujo:
 * 1. Lee la URL del dataset (baseUrl) desde sourceDataAddress
 * 2. Verifica si existe señal de anonimizacion desde Control Plane (kanon.enabled en flowProperties)
 * 3. Si NO hay señal de anonimizacion -> descarga y retorna dataset original
 * 4. Si hay señal de anonimizacion -> valida parametros tecnicos, llama servicio externo y extrae dataset anonimizado
 */
public class KanonimizationHttpDataSource implements DataSource {

    private static final Pattern FALLBACK_ANON_DATASET_PATTERN = Pattern.compile("dataset_anonymized(?:_.*)?\\..+");
    private static final String EDC_NAMESPACE = "https://w3id.org/edc/v0.0.1/ns/";
    private static final String KANON_ENABLED = "kanon.enabled";
    private static final String KANON_ENABLED_NS = EDC_NAMESPACE + KANON_ENABLED;
    private static final String KANON_REQUIRED = "kanon.anonymization.required";
    private static final String KANON_REQUIRED_NS = EDC_NAMESPACE + KANON_REQUIRED;
    private static final String KANON_POLICY_CONFIG_URL = "kanon.policyConfigUrl";
    private static final String KANON_POLICY_CONFIG_URL_NS = EDC_NAMESPACE + KANON_POLICY_CONFIG_URL;
    private static final String KANON_ASSET_ID = "kanon.assetId";
    private static final String KANON_ASSET_ID_NS = EDC_NAMESPACE + KANON_ASSET_ID;

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

    @Override
    public StreamResult<Stream<Part>> openPartStream() {
        try {
            // 1. Obtiene URL del dataset original
            var datasetUrl = asString(sourceDataAddress.getProperty("baseUrl"));
            if (datasetUrl == null || datasetUrl.isBlank()) {
                return error("[K-ANON][DP] Missing baseUrl for request " + requestId);
            }

            // 2. Resuelve señal contractual de anonimizacion y parametros tecnicos propagados desde Control Plane
            var resolvedAgreementId = firstNonBlank(agreementId, flowProperties.get("agreementId"));
            var enabledValue = firstNonBlank(
                    getFirst(flowProperties, KANON_ENABLED, KANON_ENABLED_NS, KANON_REQUIRED, KANON_REQUIRED_NS),
                    getFirst(sourceDataAddress, KANON_ENABLED, KANON_ENABLED_NS, KANON_REQUIRED, KANON_REQUIRED_NS)
            );
            var policyConfigUrl = firstNonBlank(
                    getFirst(flowProperties, KANON_POLICY_CONFIG_URL, KANON_POLICY_CONFIG_URL_NS),
                    getFirst(sourceDataAddress, KANON_POLICY_CONFIG_URL, KANON_POLICY_CONFIG_URL_NS)
            );
            var assetId = firstNonBlank(
                    getFirst(flowProperties, KANON_ASSET_ID, KANON_ASSET_ID_NS, "assetId"),
                    firstNonBlank(getFirst(sourceDataAddress, KANON_ASSET_ID, KANON_ASSET_ID_NS), flowAssetId)
            );
            var anonymizationEnabled = resolveAnonymizationEnabled(enabledValue, policyConfigUrl);

            // 3. Si NO existe señal de anonimizacion -> retorna dataset original
            if (!anonymizationEnabled) {
                var original = client.downloadFile(datasetUrl);
                var mediaType = mediaTypeFromUrl(datasetUrl);
                var part = new InMemoryPart(fileNameFromUrl(datasetUrl), original, mediaType);
                return success(Stream.of(part));
            }

            if (hasExplicitEnabledFlag(enabledValue) && (policyConfigUrl == null || policyConfigUrl.isBlank())) {
                return error("[K-ANON][DP] requestId=%s processId=%s agreementId=%s assetId=%s anonymization enabled but kanon.policyConfigUrl is missing".formatted(requestId, processId, resolvedAgreementId, assetId));
            }

            // 4. Existe señal de anonimizacion -> ejecuta anonimizacion via servicio externo
            var datasetFormat = datasetFormatFromUrl(datasetUrl);
            monitor.info("[K-ANON][DP] detection=true requestId=%s processId=%s agreementId=%s assetId=%s".formatted(requestId, processId, resolvedAgreementId, assetId));

            var zipBytes = client.anonymizeFromUrls(datasetUrl, policyConfigUrl, datasetFormat);
            var anonymizedFile = extractAnonymizedDataset(zipBytes, datasetFormat);
            var part = new InMemoryPart(anonymizedFile.fileName(), anonymizedFile.content(), mediaTypeFromUrl(anonymizedFile.fileName()));
            return success(Stream.of(part));
        } catch (Exception e) {
            return error("[K-ANON][DP] requestId=%s processId=%s agreementId=%s failed: %s".formatted(requestId, processId, agreementId, e.getMessage()));
        }
    }

    @Override
    public void close() {
        // No hay recursos persistentes que liberar
    }

    /**
     * Extrae el dataset anonimizado del ZIP de respuesta.
     * Prioriza archivo esperado segun formato (dataset_anonymized.csv/json/xlsx)
     * Fallback: cualquier archivo que coincida con dataset_anonymized*
     */
    private ExtractedFile extractAnonymizedDataset(byte[] zipBytes, String datasetFormat) {
        ExtractedFile fallback = null;
        var seenEntries = new StringBuilder();
        var preferredFileName = preferredAnonymizedFileName(datasetFormat);

        try (var zipInputStream = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                var fileName = normalizeFileName(entry.getName());
                seenEntries.append(seenEntries.length() > 0 ? ", " : "").append(fileName);

                // Lee contenido del archivo en el ZIP
                var content = readAllBytes(zipInputStream);

                // Prioridad 1: archivo con nombre exacto esperado
                if (preferredFileName.equalsIgnoreCase(fileName)) {
                    return new ExtractedFile(fileName, content);
                }

                // Prioridad 2: cualquier dataset_anonymized* como fallback
                if (fallback == null && FALLBACK_ANON_DATASET_PATTERN.matcher(fileName).matches()) {
                    fallback = new ExtractedFile(fileName, content);
                }
            }

            // Retorna fallback si existe
            if (fallback != null) {
                return fallback;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to unzip anonymization response: " + e.getMessage(), e);
        }

        throw new RuntimeException("Anonymization response zip does not contain expected '%s' nor dataset_anonymized* fallback. Entries found: [%s]".formatted(preferredFileName, seenEntries));
    }

    // Determina el nombre de archivo preferido segun formato de dataset
    private String preferredAnonymizedFileName(String datasetFormat) {
        return switch (datasetFormat.toLowerCase()) {
            case "json" -> "dataset_anonymized.json";
            case "excel" -> "dataset_anonymized.xlsx";
            default -> "dataset_anonymized.csv";
        };
    }

    // Extrae nombre de archivo de una ruta completa dentro del ZIP
    private String normalizeFileName(String entryName) {
        var index = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
        return (index >= 0 && index + 1 < entryName.length()) 
                ? entryName.substring(index + 1) 
                : entryName;
    }

    // Lee todos los bytes del stream actual (entrada de ZIP)
    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        var out = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // Detecta formato logico desde extension de URL
    private String datasetFormatFromUrl(String datasetUrl) {
        var lower = datasetUrl.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return "excel";
        if (lower.endsWith(".csv")) return "csv";
        return "binary";
    }

    // Determina media type desde extension de archivo
    private String mediaTypeFromUrl(String value) {
        var lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        return "application/octet-stream";
    }

    // Convierte URL simple a nombre de archivo
    private String fileNameFromUrl(String url) {
        var lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 1 < url.length()) {
            return url.substring(lastSlash + 1);
        }
        return "dataset";
    }

    // Retorna el primer valor no vacio/blanco
    private String firstNonBlank(String first, String second) {
        return (first != null && !first.isBlank()) ? first : second;
    }

    private boolean resolveAnonymizationEnabled(String enabledValue, String policyConfigUrl) {
        if (hasExplicitEnabledFlag(enabledValue)) {
            return Boolean.parseBoolean(enabledValue.trim());
        }
        return policyConfigUrl != null && !policyConfigUrl.isBlank();
    }

    private boolean hasExplicitEnabledFlag(String enabledValue) {
        return enabledValue != null && !enabledValue.isBlank();
    }

    private String getFirst(Map<String, String> values, String... keys) {
        for (var key : keys) {
            var value = values.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String getFirst(DataAddress dataAddress, String... keys) {
        for (var key : keys) {
            var value = asString(dataAddress.getProperty(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    // Conversion segura de Object a String
    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    // Representa un archivo extraido del ZIP de respuesta
    private record ExtractedFile(String fileName, byte[] content) {
    }

    // Implementacion de Part para retornar contenido en memoria
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
