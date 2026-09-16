package io.github.wpunit13.trino;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.wpunit13.trino.cache.CacheKeyCalculator;
import io.github.wpunit13.trino.cache.DecisionCache;
import io.github.wpunit13.trino.client.CircuitBreaker;
import io.github.wpunit13.trino.client.OpaClientException;
import io.github.wpunit13.trino.client.OpaHttpClient;
import io.github.wpunit13.trino.client.OpaResponseParser;
import io.github.wpunit13.trino.config.OpaConfig;
import io.github.wpunit13.trino.marshal.OpaRequestMarshaller;
import io.github.wpunit13.trino.metrics.OpaMetrics;
import io.github.wpunit13.trino.sql.SqlExpressionValidator;
import io.trino.spi.QueryId;
import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.security.AccessDeniedException;
import io.trino.spi.security.Identity;
import io.trino.spi.security.SystemSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live TLS handshake test (§8.5): proves the full chain — truststore load,
 * TLS handshake against a self-signed OPA endpoint, request, response —
 * and that TLS verification actually rejects untrusted certificates.
 */
class OpaTlsHandshakeIntegrationTest
{
    private static final CatalogSchemaTableName TABLE = new CatalogSchemaTableName(
            "lakehouse", new SchemaTableName("finance", "salaries"));

    private WireMockServer opa;
    private java.nio.file.Path truststorePath;

    private static SystemSecurityContext context()
    {
        Identity identity = Identity.forUser("alice").build();
        return new SystemSecurityContext(identity, QueryId.valueOf("20260905_001234_00001_abcde"), Instant.now());
    }

    @BeforeEach
    void setUp() throws Exception
    {
        java.nio.file.Path httpsKeystore = generateLocalhostKeystore();
        truststorePath = exportCertificateAsTruststore(httpsKeystore);
        opa = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .dynamicHttpsPort()
                .keystorePath(httpsKeystore.toString())
                .keystorePassword("password")
                .keystoreType("PKCS12"));
        opa.start();
    }

    /**
     * Generates a self-signed PKCS12 keystore with CN=localhost (+SAN localhost) via
     * the JDK's keytool, so the test exercises REAL hostname + chain verification.
     */
    private static java.nio.file.Path generateLocalhostKeystore() throws Exception
    {
        java.nio.file.Path keystore = java.nio.file.Files.createTempFile("opa-https", ".p12");
        java.nio.file.Files.deleteIfExists(keystore); // keytool refuses to overwrite
        String keytool = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(
                keytool, "-genkeypair",
                "-alias", "opa",
                "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keypass", "password", "-storepass", "password",
                "-storetype", "PKCS12",
                "-validity", "30",
                "-keystore", keystore.toString())
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + new String(process.getInputStream().readAllBytes()));
        }
        return keystore;
    }

    /**
     * Extracts the self-signed certificate from the generated keystore and writes it
     * into a PKCS12 truststore — exactly the artifact an ops team would receive from
     * the OPA host.
     */
    private static java.nio.file.Path exportCertificateAsTruststore(java.nio.file.Path httpsKeystore) throws Exception
    {
        KeyStore serverKeyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = java.nio.file.Files.newInputStream(httpsKeystore)) {
            serverKeyStore.load(in, "password".toCharArray());
        }
        String alias = serverKeyStore.aliases().nextElement();
        Certificate certificate = serverKeyStore.getCertificate(alias);
        assertThat(certificate).as("self-signed server certificate").isNotNull();

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("opa", certificate);
        java.nio.file.Path path = java.nio.file.Files.createTempFile("opa-truststore", ".p12");
        try (var out = java.nio.file.Files.newOutputStream(path)) {
            trustStore.store(out, "changeit".toCharArray());
        }
        return path;
    }

    @AfterEach
    void tearDown()
    {
        opa.stop();
    }

    private OpaAccessControl newAccessControl(String endpointUrl, javax.net.ssl.SSLContext sslContext)
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl(endpointUrl);
        config.setTimeoutMs(1000);
        config.setRetryMax(0);
        config.validate();
        return new OpaAccessControl(
                config,
                new OpaHttpClient(endpointUrl, 1000, 0, 0, new CircuitBreaker(10, 10_000), null, sslContext),
                new OpaResponseParser(OpaConfig.SUPPORTED_SCHEMA_VERSION),
                new OpaRequestMarshaller(),
                new CacheKeyCalculator(),
                new DecisionCache(false, 10_000, 30, 2, List.of()),
                new SqlExpressionValidator(List.of()),
                OpaMetrics.createDefault(),
                line -> {});
    }

    @Test
    void tlsHandshakeSucceedsWithTrustedCertificateAndDecisionRoundTrips() throws Exception
    {
        javax.net.ssl.SSLContext sslContext = OpaHttpClient.sslContextWithTruststore(
                truststorePath.toString(), "changeit".toCharArray());

        String httpsUrl = "https://localhost:" + opa.httpsPort(); // hostname must match the cert (localhost)
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withBody("{\"result\": {\"schema_version\": 1, \"result\": true}}")));

        OpaAccessControl accessControl = newAccessControl(httpsUrl, sslContext);
        // Full HTTPS round-trip: truststore load + hostname verification + handshake + decision.
        accessControl.checkCanCreateTable(context(), TABLE, Map.of());
    }

    @Test
    void tlsHandshakeFailsClosedAgainstUntrustedCertificate() throws Exception
    {
        // No custom SSLContext → JDK default truststore → the self-signed
        // certificate must be REJECTED (proving verification is real, not decorative).
        String httpsUrl = "https://localhost:" + opa.httpsPort();
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withBody("{\"result\": {\"schema_version\": 1, \"result\": true}}")));

        OpaAccessControl accessControl = newAccessControl(httpsUrl, null);
        assertThatThrownBy(() -> accessControl.checkCanCreateTable(context(), TABLE, Map.of()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("fail closed");
    }

    @Test
    void truststorePasswordFileIsSupportedEndToEnd() throws Exception
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("https://localhost:" + opa.httpsPort());
        config.setTlsEnabled(true);
        config.setTlsTruststorePath(truststorePath.toString());
        java.nio.file.Path passwordFile = java.nio.file.Files.createTempFile("ts-pass", ".txt");
        java.nio.file.Files.writeString(passwordFile, "changeit");
        config.setTlsTruststorePassword("file://" + passwordFile);
        config.validate(); // password file accepted; literal would fail fast

        javax.net.ssl.SSLContext sslContext = OpaHttpClient.sslContextWithTruststore(
                config.getTlsTruststorePath(), config.resolvedTruststorePassword());
        assertThat(sslContext).isNotNull();
    }

    @Test
    void literalTruststorePasswordIsRejected()
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("https://localhost:8181");
        config.setTlsEnabled(true);
        config.setTlsTruststorePath(truststorePath.toString()); // existing file so the path check passes
        config.setTlsTruststorePassword("super-secret-in-plaintext");
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file:// reference");
    }
}
