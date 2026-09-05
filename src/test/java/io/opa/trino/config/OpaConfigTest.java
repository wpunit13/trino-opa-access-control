package io.opa.trino.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Startup validation (§9): required keys, valid values, fail fast on unknown keys. */
class OpaConfigTest
{
    private Map<String, String> validProperties()
    {
        Map<String, String> properties = new HashMap<>();
        properties.put("access-control.name", "opa-access-control");
        properties.put("opa.endpoint.url", "http://127.0.0.1:8181");
        return properties;
    }

    @Test
    void minimalConfigIsValid()
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("http://127.0.0.1:8181");
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void endpointIsRequired()
    {
        OpaConfig config = new OpaConfig();
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opa.endpoint.url");
    }

    @Test
    void endpointMustBeHttp()
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("ftp://example.com");
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid opa.endpoint.url");
    }

    @Test
    void timeoutMustBePositive()
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("http://127.0.0.1:8181");
        config.setTimeoutMs(0);
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout-ms");
    }

    @Test
    void sqlModeMustBeKnown()
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("http://127.0.0.1:8181");
        config.setSqlMode("bogus");
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opa.sql.mode");
    }

    @Test
    void tlsRequiresTruststore()
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("http://127.0.0.1:8181");
        config.setTlsEnabled(true);
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("truststore");
    }
}
