package io.github.wpunit13.trino.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;

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
 *
 * Hardening (§8.5): optional bearer-token auth and TLS with a custom truststore
 * (mTLS-style server verification) when the PDP is not on loopback.
 */
public final class OpaHttpClient
{
    private static final SecureRandom JITTER_RANDOM = new SecureRandom();

    private final HttpClient client;
    private final String endpointUrl;
    private final int timeoutMs;
    private final int retryMax;
    private final int retryBackoffMs;
    private final CircuitBreaker circuitBreaker;
    private final String bearerToken;
    private final boolean tls;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpaHttpClient(String endpointUrl, int timeoutMs, int retryMax, int retryBackoffMs)
    {
        this(endpointUrl, timeoutMs, retryMax, retryBackoffMs, null, null, null);
    }

    public OpaHttpClient(String endpointUrl, int timeoutMs, int retryMax, int retryBackoffMs, CircuitBreaker circuitBreaker)
    {
        this(endpointUrl, timeoutMs, retryMax, retryBackoffMs, circuitBreaker, null, null);
    }

    public OpaHttpClient(String endpointUrl, int timeoutMs, int retryMax, int retryBackoffMs,
                         CircuitBreaker circuitBreaker, String bearerToken, SSLContext sslContext)
    {
        this.endpointUrl = endpointUrl.endsWith("/") ? endpointUrl.substring(0, endpointUrl.length() - 1) : endpointUrl;
        this.timeoutMs = timeoutMs;
        this.retryMax = retryMax;
        this.retryBackoffMs = retryBackoffMs;
        this.circuitBreaker = circuitBreaker;
        this.bearerToken = bearerToken;
        this.tls = sslContext != null;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs));
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.client = builder.build();
    }

    /** Builds an SSLContext that trusts only the given PKCS12 truststore (§8.5). */
    public static SSLContext sslContextWithTruststore(String truststorePath, char[] password)
            throws GeneralSecurityException, IOException
    {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(truststorePath))) {
            trustStore.load(in, password);
        }
        var tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), new java.security.SecureRandom());
        return context;
    }

    public boolean usesTls()
    {
        return tls;
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

        String body = serializeBody(input);
        HttpRequest request = buildRequest(path, body);

        Exception lastError = null;
        for (int attempt = 0; attempt <= retryMax; attempt++) {
            if (attempt > 0 && retryBackoffMs > 0) {
                sleepWithJitter();
            }
            Outcome outcome = attempt(request, attempt);
            if (outcome.success()) {
                return outcome.json();
            }
            lastError = outcome.error();
        }
        recordFailure();
        throw new OpaClientException("OPA request failed after retries", lastError);
    }

    private String serializeBody(Map<String, Object> input)
    {
        try {
            // OPA's data API expects the marshaled Contract-1 input wrapped in
            // the server envelope: {"input": {...}}. Sending the bare input map
            // makes OPA evaluate with an undefined input (everything denies).
            return mapper.writeValueAsString(Map.of("input", input));
        }
        catch (IOException e) {
            recordFailure();
            throw new OpaClientException("Unable to serialize OPA request", e);
        }
    }

    private HttpRequest buildRequest(String path, String body)
    {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl + path))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null) {
            requestBuilder.header("Authorization", "Bearer " + bearerToken);
        }
        return requestBuilder.build();
    }

    /**
     * Performs a single request attempt. Returns a success {@link Outcome} on a
     * 200 with a well-formed body, a retry {@link Outcome} for transient failures
     * (transport error, retryable 5xx), or throws (fail closed) on a terminal
     * failure.
     */
    private Outcome attempt(HttpRequest request, int attempt)
    {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e) {
            return Outcome.retry(e); // transient transport failure: retry
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure();
            throw new OpaClientException("Interrupted while calling OPA", e);
        }

        int status = response.statusCode();
        if (status >= 500) {
            // Transient server-side failure: retry; on the last attempt fail closed
            // with a clear status message.
            if (attempt < retryMax) {
                return Outcome.retry(new OpaClientException("OPA returned 5xx status " + status));
            }
            recordFailure();
            throw new OpaClientException("OPA returned non-200 status " + status + " (retries exhausted)");
        }
        if (status != 200) {
            recordFailure();
            throw new OpaClientException("OPA returned non-200 status " + status);
        }
        return Outcome.success(parseBody(response.body()));
    }

    private JsonNode parseBody(String body)
    {
        try {
            JsonNode json = mapper.readTree(body);
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
        // Jitter in [backoff/2, 3*backoff/2] (±50%), using a secure PRNG (jitter is
        // not security-sensitive, but SecureRandom keeps the analyzer's PRNG rule clean).
        long base = retryBackoffMs / 2;
        long span = retryBackoffMs;
        long jittered = base + JITTER_RANDOM.nextLong(span + 1);
        try {
            Thread.sleep(Math.max(0, jittered));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpaClientException("Interrupted while backing off", e);
        }
    }

    /** Carries the result of one request attempt: either a parsed body or a retryable error. */
    private record Outcome(JsonNode json, Exception error)
    {
        static Outcome success(JsonNode json)
        {
            return new Outcome(json, null);
        }

        static Outcome retry(Exception error)
        {
            return new Outcome(null, error);
        }

        boolean success()
        {
            return json != null;
        }
    }
}