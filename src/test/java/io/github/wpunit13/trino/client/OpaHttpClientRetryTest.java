package io.github.wpunit13.trino.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §6.3: retry with jitter (assert call counts under stubbed failures) + breaker wiring. */
class OpaHttpClientRetryTest
{
    private WireMockServer opa;

    @BeforeEach
    void setUp()
    {
        opa = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        opa.start();
    }

    @AfterEach
    void tearDown()
    {
        opa.stop();
    }

    private OpaHttpClient client(int retryMax, int backoffMs, CircuitBreaker breaker)
    {
        return new OpaHttpClient("http://127.0.0.1:" + opa.port(), 500, retryMax, backoffMs, breaker);
    }

    private Map<String, Object> input()
    {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schema_version", 1);
        return input;
    }

    @Test
    void retriesTransient5xxAndSucceeds()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .inScenario("flaky")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"));
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .inScenario("flaky")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withBody("{\"result\": {\"schema_version\": 1, \"result\": true}}")));

        JsonNode response = client(2, 10, null).query("/v1/data/trino/allow", input());
        assertThat(response.get("result").get("result").asBoolean()).isTrue();
        // 2 attempts: 1 failure + 1 success (retry happened)
        opa.verify(2, postRequestedFor(urlEqualTo("/v1/data/trino/allow")));
    }

    @Test
    void retriesExhaustedFailsClosed()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withStatus(503)));
        // retryMax = 2 → 1 original + 2 retries = 3 calls
        assertThatThrownBy(() -> client(2, 5, null).query("/v1/data/trino/allow", input()))
                .isInstanceOf(OpaClientException.class)
                .hasMessageContaining("retries exhausted");
        opa.verify(3, postRequestedFor(urlEqualTo("/v1/data/trino/allow")));
    }

    @Test
    void transportErrorsAreRetried()
    {
        // Port with no listener → connection refused on every attempt
        int deadPort = opa.port();
        opa.stop();
        OpaHttpClient deadClient = new OpaHttpClient("http://127.0.0.1:" + deadPort, 500, 1, 5, null);
        assertThatThrownBy(() -> deadClient.query("/v1/data/trino/allow", input()))
                .isInstanceOf(OpaClientException.class)
                .hasMessageContaining("after retries");
        // cannot verify request count on a dead server; the exception message proves retries ran
    }

    @Test
    void non5xxNon200FailsImmediatelyWithoutRetry()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> client(2, 5, null).query("/v1/data/trino/allow", input()))
                .isInstanceOf(OpaClientException.class)
                .hasMessageContaining("non-200");
        // 4xx is not transient: only 1 call, no retries
        opa.verify(1, postRequestedFor(urlEqualTo("/v1/data/trino/allow")));
    }

    @Test
    void breakerFailuresAreCountedAcrossCalls()
    {
        CircuitBreaker breaker = new CircuitBreaker(2, 60_000);
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withStatus(503)));
        OpaHttpClient failing = client(0, 0, breaker);

        assertThatThrownBy(() -> failing.query("/v1/data/trino/allow", input()))
                .isInstanceOf(OpaClientException.class);
        assertThatThrownBy(() -> failing.query("/v1/data/trino/allow", input()))
                .isInstanceOf(OpaClientException.class);
        opa.verify(2, postRequestedFor(urlEqualTo("/v1/data/trino/allow")));

        // Breaker now open: the next call fails fast WITHOUT reaching the wire
        assertThatThrownBy(() -> failing.query("/v1/data/trino/allow", input()))
                .isInstanceOf(OpaClientException.class)
                .hasMessageContaining("circuit breaker is open");
        opa.verify(2, postRequestedFor(urlEqualTo("/v1/data/trino/allow")));
    }

    @Test
    void successfulResponseClosesBreaker()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withBody("{\"result\": {\"schema_version\": 1, \"result\": true}}")));

        // openDurationMs=0 → the breaker immediately half-opens after a failure and the
        // successful probe closes it.
        CircuitBreaker breaker = new CircuitBreaker(1, 0);
        breaker.recordFailure();
        assertThat(client(0, 0, breaker).query("/v1/data/trino/allow", input()).has("result")).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
