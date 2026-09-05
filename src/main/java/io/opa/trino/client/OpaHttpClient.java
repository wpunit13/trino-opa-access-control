package io.opa.trino.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal async-capable HTTP client for OPA data-document queries.
 *
 * Fail-closed contract (ARCHITECTURE.md §7): any transport error, timeout,
 * non-200 status, or OPA error body results in {@link OpaClientException};
 * callers must convert that into an {@code AccessDeniedException}.
 */
public final class OpaHttpClient
{
    private final HttpClient client;
    private final String endpointUrl;
    private final int timeoutMs;
    private final int retryMax;
    private final int retryBackoffMs;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpaHttpClient(String endpointUrl, int timeoutMs, int retryMax, int retryBackoffMs)
    {
        this.endpointUrl = endpointUrl.endsWith("/") ? endpointUrl.substring(0, endpointUrl.length() - 1) : endpointUrl;
        this.timeoutMs = timeoutMs;
        this.retryMax = retryMax;
        this.retryBackoffMs = retryBackoffMs;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    /**
     * POSTs the marshaled input to the given OPA data path and returns the parsed
     * response body. Retries transient transport failures (read-only, idempotent calls).
     *
     * @throws OpaClientException on any failure (fail closed)
     */
    public JsonNode query(String path, Map<String, Object> input)
    {
        String body;
        try {
            body = mapper.writeValueAsString(input);
        }
        catch (IOException e) {
            throw new OpaClientException("Unable to serialize OPA request", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl + path))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        IOException lastTransportError = null;
        for (int attempt = 0; attempt <= retryMax; attempt++) {
            if (attempt > 0 && retryBackoffMs > 0) {
                try {
                    Thread.sleep(retryBackoffMs);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new OpaClientException("Interrupted while calling OPA", e);
                }
            }
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            }
            catch (IOException e) {
                lastTransportError = e;
                continue; // transient transport failure: retry
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpaClientException("Interrupted while calling OPA", e);
            }

            if (response.statusCode() != 200) {
                throw new OpaClientException("OPA returned non-200 status " + response.statusCode());
            }
            try {
                JsonNode json = mapper.readTree(response.body());
                if (json == null || json.isMissingNode()) {
                    throw new OpaClientException("OPA returned a malformed (empty) response body");
                }
                // OPA's own error envelope ({code, message}) must fail closed.
                if (json.has("code") && json.has("message")) {
                    throw new OpaClientException("OPA error response: " + json.get("code").asText()
                            + " " + json.get("message").asText());
                }
                return json;
            }
            catch (IOException e) {
                throw new OpaClientException("OPA returned a malformed response body", e);
            }
        }
        throw new OpaClientException("OPA request failed after retries", lastTransportError);
    }
}
