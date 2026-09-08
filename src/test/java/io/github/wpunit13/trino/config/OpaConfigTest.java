package io.github.wpunit13.trino.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
    void sqlModeDefaultsToSafe()
    {
        // D6 (ROADMAP.md M7): safe is the default before first production deployment.
        OpaConfig config = new OpaConfig();
        assertThat(config.getSqlMode()).isEqualTo("safe");
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

    @Test
    void tlsTruststoreMustExist()
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("http://127.0.0.1:8181");
        config.setTlsEnabled(true);
        config.setTlsTruststorePath("/nonexistent/truststore.p12");
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void tokenFileMustExist()
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("http://127.0.0.1:8181");
        config.setAuthToken("file:///nonexistent/token");
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth.token");
    }

    @Test
    void tokenFileIsResolvedAtStartup(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws Exception
    {
        java.nio.file.Path tokenFile = tempDir.resolve("opa-token");
        java.nio.file.Files.writeString(tokenFile, "  file-secret-42\n");

        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("http://127.0.0.1:8181");
        config.setAuthToken("file://" + tokenFile);
        config.validate(); // must not throw
        assertThat(config.resolvedAuthToken()).isEqualTo("file-secret-42");
    }

    @Test
    void literalTokenIsUsedAsIs()
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("http://127.0.0.1:8181");
        config.setAuthToken("literal-secret");
        config.validate();
        assertThat(config.resolvedAuthToken()).isEqualTo("literal-secret");
    }

    @Test
    void maxInClauseSizeMustBePositive()
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("http://127.0.0.1:8181");
        config.setMaxInClauseSize(0);
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-in-clause-size");
    }
}
