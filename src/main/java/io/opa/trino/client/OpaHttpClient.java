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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Minimal async-capable HTTP client for OPA data-document queries.
 *
 * Fail-closed contract (ARCHITECTURE.md §7): any transport error, timeout,
 * non-200 status, or OPA error body results in {@link OpaClientException};
 * callers must convert that into an {@code AccessDeniedException}.
 *
 * Resilience (§6.3): OPA decision calls are read-only and idempotent, so transient
 * failures are retried with exponential backoff plus jitter. An optional
 * {@link CircuitBreaker} wraps dispatch so a degraded PDP fails fast.
 */
public final class OpaHttpClient
{
    private static final double JITTER_FACTOR = 0.5; // ±50% of the backoff

    private final HttpClient client;
    private final String endpointUrl;
    private final int timeoutMs;
    private final int retryMax;
    private final int retryBackoffMs;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpaHttpClient(String endpointUrl, int timeoutMs, int retryMax, int retryBackoffMs)
    {
        this(endpointUrl, timeoutMs, retryMax, retryBackoffMs, null);
    }

    public OpaHttpClient(String endpointUrl, int timeoutMs, int retryMax, int retryBackoffMs, CircuitBreaker circuitBreaker)
    {
        this.endpointUrl = endpointUrl.endsWith("/") ? endpointUrl.substring(0, endpointUrl.length() - 1) : endpointUrl;
        this.timeoutMs = timeoutMs;
        this.retryMax = retryMax;
        this.retryBackoffMs = retryBackoffMs;
        this.circuitBreaker = circuitBreaker;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    /**
     * POSTs the marshaled input to the given OPA data path and returns the parsed
     * response body. Retries transient transport failures and 5xx responses.
     *
     * @throws OpaClientException on any failure (fail closed), including an open circuit
     */
    public JsonNode query(String path, Map<String, Object> input)
    {
        if (circuitBreaker != null) {
            circuitBreaker.acquire(); // fail fast while the breaker is open
        }

        String body;
        try {
            body = mapper.writeValueAsString(input);
        }
        catch (IOException e) {
            recordFailure();
            throw new OpaClientException("Unable to serialize OPA request", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl + path))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        Exception lastError = null;
        for (int attempt = 0; attempt <= retryMax; attempt++) {
            if (attempt > 0 && retryBackoffMs > 0) {
                sleepWithJitter();
            }
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            }
            catch (IOException e) {
                lastError = e;
                continue; // transient transport failure: retry
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordFailure();
                throw new OpaClientException("Interrupted while calling OPA", e);
            }

            if (response.statusCode() >= 500) {
                // Transient server-side failure: retry; on the last attempt fail closed
                // with a clear status message.
                if (attempt == retryMax) {
                    recordFailure();
                    throw new OpaClientException("OPA returned non-200 status " + response.statusCode()
                            + " (retries exhausted)");
                }
                lastError = new OpaClientException("OPA returned 5xx status " + response.statusCode());
                continue;
            }
            if (response.statusCode() != 200) {
                recordFailure();
                throw new OpaClientException("OPA returned non-200 status " + response.statusCode());
            }
            try {
                JsonNode json = mapper.readTree(response.body());
                if (json == null || json.isMissingNode()) {
                    recordFailure();
                    throw new OpaClientException("OPA returned a malformed (empty) response body");
                }
                // OPA's own error envelope ({code, message}) must fail closed.
                if (json.has("code") && json.has("message")) {
                    recordFailure();
                    throw new OpaClientException("OPA error response: " + json.get("code").asText()
                            + " " + json.get("message").asText());
                }
                recordSuccess();
                return json;
            }
            catch (IOException e) {
                recordFailure();
                throw new OpaClientException("OPA returned a malformed response body", e);
            }
        }
        recordFailure();
        throw new OpaClientException("OPA request failed after retries", lastError);
    }

    private void recordSuccess()
    {
        if (circuitBreaker != null) {
            circuitBreaker.recordSuccess();
        }
    }

    private void recordFailure()
    {
        if (circuitBreaker != null) {
            circuitBreaker.recordFailure();
        }
    }

    private void sleepWithJitter()
    {
        long jittered = (long) (retryBackoffMs * (1.0 - JITTER_FACTOR
                + ThreadLocalRandom.current().nextDouble() * 2 * JITTER_FACTOR));
        try {
            Thread.sleep(Math.max(0, jittered));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpaClientException("Interrupted while backing off", e);
        }
    }
}
