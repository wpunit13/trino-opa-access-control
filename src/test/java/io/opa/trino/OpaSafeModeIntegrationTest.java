package io.opa.trino;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.opa.trino.cache.CacheKeyCalculator;
import io.opa.trino.cache.DecisionCache;
import io.opa.trino.client.CircuitBreaker;
import io.opa.trino.client.OpaHttpClient;
import io.opa.trino.client.OpaResponseParser;
import io.opa.trino.config.OpaConfig;
import io.opa.trino.marshal.OpaRequestMarshaller;
import io.opa.trino.metrics.OpaMetrics;
import io.opa.trino.sql.SqlExpressionValidator;
import io.trino.spi.QueryId;
import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.security.AccessDeniedException;
import io.trino.spi.security.Identity;
import io.trino.spi.security.SystemSecurityContext;
import io.trino.spi.security.ViewExpression;
import io.trino.spi.type.BigintType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milestone 4: safe mode end-to-end (descriptor → plugin-rendered SQL) and
 * bearer-token auth (§8.5).
 */
class OpaSafeModeIntegrationTest
{
    private static final CatalogSchemaTableName TABLE = new CatalogSchemaTableName(
            "lakehouse", new SchemaTableName("finance", "salaries"));

    private WireMockServer opa;
    private OpaAccessControl accessControl;
    private OpaConfig config;

    private static SystemSecurityContext context()
    {
        Identity identity = Identity.forUser("alice")
                .withGroups(Set.of("engineering"))
                .build();
        return new SystemSecurityContext(identity, QueryId.valueOf("20260905_001234_00001_abcde"), Instant.now());
    }

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

    private OpaAccessControl newAccessControl(String sqlMode, String token)
    {
        return newAccessControl(sqlMode, token, 1000);
    }

    private OpaAccessControl newAccessControl(String sqlMode, String token, int maxInClauseSize)
    {
        config = new OpaConfig();
        config.setEndpointUrl("http://127.0.0.1:" + opa.port());
        config.setTimeoutMs(250);
        config.setRetryMax(0);
        config.setSqlMode(sqlMode);
        config.setMaxInClauseSize(maxInClauseSize);
        if (token != null) {
            config.setAuthToken(token);
        }
        config.validate();
        return new OpaAccessControl(
                config,
                new OpaHttpClient(config.getEndpointUrl(), 250, 0, 0, new CircuitBreaker(10, 10_000),
                        config.resolvedAuthToken(), null),
                new OpaResponseParser(OpaConfig.SUPPORTED_SCHEMA_VERSION),
                new OpaRequestMarshaller(),
                new CacheKeyCalculator(),
                new DecisionCache(false, 10_000, 30, 2, List.of()),
                new SqlExpressionValidator(List.of()),
                OpaMetrics.createDefault(),
                line -> {});
    }

    // ------------------------------------------------------------------
    // Safe mode happy path
    // ------------------------------------------------------------------

    @Test
    void safeModeRendersDescriptorIntoRowFilter()
    {
        accessControl = newAccessControl("safe", null);
        opa.stubFor(post(urlEqualTo("/v1/data/trino/row_filters"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": ["
                                + "{\"op\": \"in\", \"column\": \"org_unit_id\", \"values\": [\"dept_eng_01\", \"dept_eng_core\"]},"
                                + "{\"op\": \"eq\", \"column\": \"country_iso\", \"values\": [\"DE\"]}]}}")));

        List<ViewExpression> filters = accessControl.getRowFilters(context(), TABLE);
        assertThat(filters).hasSize(2);
        assertThat(filters.get(0).getExpression()).isEqualTo("org_unit_id IN ('dept_eng_01', 'dept_eng_core')");
        assertThat(filters.get(1).getExpression()).isEqualTo("country_iso = 'DE'");
    }

    @Test
    void safeModeEscapesValuesRenderedByThePlugin()
    {
        accessControl = newAccessControl("safe", null);
        opa.stubFor(post(urlEqualTo("/v1/data/trino/row_filters"))
                .willReturn(aResponse().withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": ["
                                + "{\"op\": \"eq\", \"column\": \"legal_entity\", \"values\": [\"O'Brien\"]}]}}")));

        List<ViewExpression> filters = accessControl.getRowFilters(context(), TABLE);
        assertThat(filters.get(0).getExpression()).isEqualTo("legal_entity = 'O''Brien'");
    }

    @Test
    void safeModeRendersMaskDescriptor()
    {
        accessControl = newAccessControl("safe", null);
        opa.stubFor(post(urlEqualTo("/v1/data/trino/column_masks"))
                .willReturn(aResponse().withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": "
                                + "{\"op\": \"is_null\", \"column\": \"ssn\"}}}")));

        Optional<ViewExpression> mask = accessControl.getColumnMask(context(), TABLE, "ssn", BigintType.BIGINT);
        assertThat(mask).isPresent();
        assertThat(mask.get().getExpression()).isEqualTo("ssn IS NULL");
    }

    @Test
    void safeModeNullMaskStillMeansUnmasked()
    {
        accessControl = newAccessControl("safe", null);
        opa.stubFor(post(urlEqualTo("/v1/data/trino/column_masks"))
                .willReturn(aResponse().withBody("{\"result\": {\"schema_version\": 1, \"result\": null}}")));
        assertThat(accessControl.getColumnMask(context(), TABLE, "ssn", BigintType.BIGINT)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Safe mode fail-closed descriptor validation
    // ------------------------------------------------------------------

    @Test
    void safeModeRejectsUnsupportedOp()
    {
        accessControl = newAccessControl("safe", null);
        opa.stubFor(post(urlEqualTo("/v1/data/trino/row_filters"))
                .willReturn(aResponse().withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": ["
                                + "{\"op\": \"like\", \"column\": \"name\", \"values\": [\"%admin%\"]}]}}")));
        assertThatThrownBy(() -> accessControl.getRowFilters(context(), TABLE))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("fail closed");
    }

    @Test
    void safeModeRejectsUnsafeColumnIdentifier()
    {
        accessControl = newAccessControl("safe", null);
        opa.stubFor(post(urlEqualTo("/v1/data/trino/row_filters"))
                .willReturn(aResponse().withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": ["
                                + "{\"op\": \"eq\", \"column\": \"1=1 OR true --\", \"values\": [\"x\"]}]}}")));
        assertThatThrownBy(() -> accessControl.getRowFilters(context(), TABLE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void safeModeRejectsMissingValuesArray()
    {
        accessControl = newAccessControl("safe", null);
        opa.stubFor(post(urlEqualTo("/v1/data/trino/row_filters"))
                .willReturn(aResponse().withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": ["
                                + "{\"op\": \"in\", \"column\": \"org_unit_id\"}]}}")));
        assertThatThrownBy(() -> accessControl.getRowFilters(context(), TABLE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void safeModeRejectsOverSizedInClause()
    {
        accessControl = newAccessControl("safe", null, 2);
        StringBuilder values = new StringBuilder("[");
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                values.append(", ");
            }
            values.append("\"v").append(i).append("\"");
        }
        values.append("]");
        opa.stubFor(post(urlEqualTo("/v1/data/trino/row_filters"))
                .willReturn(aResponse().withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": ["
                                + "{\"op\": \"in\", \"column\": \"org_unit_id\", \"values\": " + values + "}]}}")));
        assertThatThrownBy(() -> accessControl.getRowFilters(context(), TABLE))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("max-in-clause-size");
    }

    // ------------------------------------------------------------------
    // Passthrough mode still works
    // ------------------------------------------------------------------

    @Test
    void passthroughModeStillAcceptsRawSql()
    {
        accessControl = newAccessControl("passthrough", null);
        opa.stubFor(post(urlEqualTo("/v1/data/trino/row_filters"))
                .willReturn(aResponse().withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": [\"tenant_id = 'org_7718'\"]}}")));
        assertThat(accessControl.getRowFilters(context(), TABLE).get(0).getExpression())
                .isEqualTo("tenant_id = 'org_7718'");
    }

    // ------------------------------------------------------------------
    // Bearer-token auth (§8.5)
    // ------------------------------------------------------------------

    @Test
    void bearerTokenIsSentOnEveryRequest()
    {
        accessControl = newAccessControl("passthrough", "secret-token-123");
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .withHeader("Authorization", equalTo("Bearer secret-token-123"))
                .willReturn(aResponse().withBody("{\"result\": {\"schema_version\": 1, \"result\": true}}")));

        accessControl.checkCanCreateTable(context(), TABLE, java.util.Map.of());
        opa.verify(1, postRequestedFor(urlEqualTo("/v1/data/trino/allow"))
                .withHeader("Authorization", equalTo("Bearer secret-token-123")));
    }

    @Test
    void noAuthorizationHeaderWhenTokenNotConfigured()
    {
        accessControl = newAccessControl("passthrough", null);
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withBody("{\"result\": {\"schema_version\": 1, \"result\": true}}")));
        accessControl.checkCanCreateTable(context(), TABLE, java.util.Map.of());

        var requests = opa.findAll(postRequestedFor(urlEqualTo("/v1/data/trino/allow")));
        assertThat(requests.get(0).header("Authorization").isPresent()).isFalse();
    }
}
