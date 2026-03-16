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

import org.eclipse.edc.spi.monitor.Monitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * HTTP client used by DP to download assets/policy and call the anonymization API.
 */
public class KanonimizationServiceClient {

    private final String serviceUrl;
    private final long timeoutSeconds;
    private final String apiKeyId;
    private final String apiKeySecret;
    private final Monitor monitor;
    private final HttpClient httpClient;

    public KanonimizationServiceClient(String serviceUrl,
                                       long timeoutSeconds,
                                       String apiKeyId,
                                       String apiKeySecret,
                                       Monitor monitor) {
        this.serviceUrl = serviceUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.apiKeyId = apiKeyId == null ? "" : apiKeyId;
        this.apiKeySecret = apiKeySecret == null ? "" : apiKeySecret;
        this.monitor = monitor;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Orchestrates download and anonymization call.
     */
    public byte[] anonymizeFromUrls(String datasetUrl, String policyUrl, String datasetFormat) {
        try {
            var datasetBytes = downloadFile(datasetUrl);
            var policyBytes = downloadFile(policyUrl);
            return sendToAnonymizationService(datasetBytes, policyBytes, datasetFormat, datasetUrl);
        } catch (Exception e) {
            throw new RuntimeException("Anonymization failed: " + describeException(e), e);
        }
    }

    /**
     * Downloads a file as bytes from URL.
     */
    public byte[] downloadFile(String urlString) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(urlString))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new IOException("Failed to download from " + urlString + ": HTTP " + response.statusCode());
            }
            return response.body();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download file from " + urlString + ": " + describeException(e), e);
        }
    }

    private byte[] sendToAnonymizationService(byte[] datasetBytes,
                                              byte[] policyBytes,
                                              String datasetFormat,
                                              String datasetUrl) throws IOException, InterruptedException {
        var boundary = UUID.randomUUID().toString();
        var body = buildMultipartBody(datasetBytes, policyBytes, boundary, datasetFormat, datasetUrl);
        var endpoint = serviceUrl.endsWith("/") ? serviceUrl + "anonymize" : serviceUrl + "/anonymize";

        var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);

        var authConfigured = hasCredentials();
        if (authConfigured) {
            requestBuilder = requestBuilder.header("Authorization", "ApiKey " + apiKeyId + ":" + apiKeySecret);
        }

        monitor.info("[K-ANON][DP] anonymization-call endpoint=%s datasetFormat=%s authConfigured=%s".formatted(endpoint, datasetFormat, authConfigured));

        var request = requestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            var errorMsg = new String(response.body(), StandardCharsets.UTF_8);
            monitor.warning("[K-ANON][DP] Anonymization service error: HTTP " + response.statusCode() + " body=" + errorMsg);
            throw new RuntimeException("Anonymization service returned HTTP " + response.statusCode());
        }

        monitor.info("[K-ANON][DP] anonymization-response status=%s bytes=%s".formatted(response.statusCode(), response.body().length));

        return response.body();
    }

    /**
     * Builds multipart payload with dataset + policy as expected by API.
     */
    private byte[] buildMultipartBody(byte[] datasetBytes,
                                      byte[] policyBytes,
                                      String boundary,
                                      String datasetFormat,
                                      String datasetUrl) throws IOException {
        var out = new ByteArrayOutputStream();

        writeString(out, "--" + boundary + "\r\n");
        writeString(out, "Content-Disposition: form-data; name=\"dataset\"; filename=\"" + fileNameFromUrl(datasetUrl) + "\"\r\n");
        writeString(out, "Content-Type: " + mediaTypeFromFormat(datasetFormat) + "\r\n\r\n");
        out.write(datasetBytes);
        writeString(out, "\r\n");

        writeString(out, "--" + boundary + "\r\n");
        writeString(out, "Content-Disposition: form-data; name=\"policy\"; filename=\"policy.json\"\r\n");
        writeString(out, "Content-Type: application/json\r\n\r\n");
        out.write(policyBytes);
        writeString(out, "\r\n");

        writeString(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    /**
     * Appends UTF-8 text chunks to multipart byte stream.
     */
    private void writeString(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Maps logical dataset format to HTTP media type.
     */
    private String mediaTypeFromFormat(String datasetFormat) {
        if ("csv".equalsIgnoreCase(datasetFormat)) {
            return "text/csv";
        }
        if ("json".equalsIgnoreCase(datasetFormat)) {
            return "application/json";
        }
        if ("excel".equalsIgnoreCase(datasetFormat)) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return "application/octet-stream";
    }

    /**
     * Extracts filename segment from URL.
     */
    private String fileNameFromUrl(String url) {
        var index = url.lastIndexOf('/');
        if (index >= 0 && index + 1 < url.length()) {
            return url.substring(index + 1);
        }
        return "dataset";
    }

    /**
     * Creates readable nested error details for logs and API responses.
     */
    private String describeException(Throwable error) {
        if (error == null) {
            return "unknown error";
        }

        var message = error.getMessage();
        if (message != null && !message.isBlank()) {
            return error.getClass().getSimpleName() + ": " + message;
        }

        var cause = error.getCause();
        if (cause != null) {
            var causeMessage = cause.getMessage();
            if (causeMessage != null && !causeMessage.isBlank()) {
                return error.getClass().getSimpleName() + " caused by " + cause.getClass().getSimpleName() + ": " + causeMessage;
            }
            return error.getClass().getSimpleName() + " caused by " + cause.getClass().getSimpleName();
        }

        return error.getClass().getSimpleName();
    }

    /**
     * Indicates whether API key credentials are present.
     */
    private boolean hasCredentials() {
        return !apiKeyId.isBlank() && !apiKeySecret.isBlank();
    }
}
