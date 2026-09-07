package io.opa.trino;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.opa.trino.cache.CacheKeyCalculator;
import io.opa.trino.cache.DecisionCache;
import io.opa.trino.client.OpaHttpClient;
import io.opa.trino.client.OpaResponseParser;
import io.opa.trino.config.OpaConfig;
import io.opa.trino.marshal.OpaRequestMarshaller;
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
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests against a WireMock stub returning the exact response shapes
 * from ARCHITECTURE.md §3.2. Covers the allow path, row filters, column masks,
 * caching, and every fail-closed mode (§7).
 */
class OpaAccessControlIntegrationTest
{
    private static final CatalogSchemaTableName TABLE = new CatalogSchemaTableName(
            "lakehouse", new SchemaTableName("finance", "salaries"));

    private WireMockServer opa;
    private OpaAccessControl accessControl;
    static final java.util.List<String> decisionLog = new java.util.ArrayList<>();

    private static SystemSecurityContext context(String user, Set<String> groups)
    {
        Identity identity = Identity.forUser(user)
                .withGroups(groups)
                .build();
        return new SystemSecurityContext(identity, QueryId.valueOf("20260905_001234_00001_abcde"), Instant.now());
    }

    @BeforeEach
    void setUp()
    {
        opa = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        opa.start();
        decisionLog.clear();
        accessControl = newAccessControl(opa.port(), 250);
    }

    @AfterEach
    void tearDown()
    {
        opa.stop();
    }

    private static OpaAccessControl newAccessControl(int port, int timeoutMs)
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("http://127.0.0.1:" + port);
        config.setTimeoutMs(timeoutMs);
        config.setRetryMax(0);
        // This class exercises passthrough-mode behavior (raw SQL strings from OPA).
        // Since D6 flipped the OpaConfig default to safe, set it explicitly here.
        config.setSqlMode("passthrough");
        config.validate();

        return new OpaAccessControl(
                config,
                new OpaHttpClient(config.getEndpointUrl(), timeoutMs, config.getRetryMax(), 0),
                new OpaResponseParser(OpaConfig.SUPPORTED_SCHEMA_VERSION),
                new OpaRequestMarshaller(),
                new CacheKeyCalculator(),
                new DecisionCache(true, 10_000, 30, 2, List.of("source_ip", "catalog_session_properties")),
                new SqlExpressionValidator(List.of()),
                io.opa.trino.metrics.OpaMetrics.createDefault(),
                decisionLog::add);
    }

    private void stubAllow(String body)
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    // ------------------------------------------------------------------
    // Allow path
    // ------------------------------------------------------------------

    @Test
    void checkCanCreateTableAllowsWhenOpaReturnsTrue()
    {
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": true}}");
        accessControl.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of());
    }

    @Test
    void checkCanCreateTableDeniesWhenOpaReturnsFalse()
    {
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": false}}");
        assertThatThrownBy(() -> accessControl.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requestCarriesMarshaledContractInput()
    {
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": true}}");
        accessControl.checkCanCreateTable(context("alice", Set.of("engineering")), TABLE, java.util.Map.of());

        var requests = opa.findAll(postRequestedFor(urlEqualTo("/v1/data/trino/allow")));
        assertThat(requests).hasSize(1);
        var body = requests.get(0).getBodyAsString();
        // OPA data-API envelope: the marshaled Contract-1 input must be wrapped
        // in {"input": ...} — a bare map evaluates with an undefined input.
        assertThat(body).startsWith("{\"input\":{");
        assertThat(body).contains("\"schema_version\":1");
        assertThat(body).contains("\"action\":\"CREATE_TABLE\"");
        assertThat(body).contains("\"user\":\"alice\"");
        assertThat(body).contains("\"groups\":[\"engineering\"]");
        assertThat(body).contains("\"catalog\":\"lakehouse\"");
        assertThat(body).contains("\"table\":\"salaries\"");
        assertThat(body).contains("\"decision_id\":\"");
    }

    // ------------------------------------------------------------------
    // Row filters / column masks (Contract 2 B/C + Contract 3/4)
    // ------------------------------------------------------------------

    @Test
    void getRowFiltersWrapsValidSqlInViewExpressions()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/row_filters"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": [\"tenant_id = 'org_7718'\", \"org_hierarchy_id IN ('dept_eng_01', 'dept_eng_core')\"]}}")));

        List<ViewExpression> filters = accessControl.getRowFilters(context("alice", Set.of()), TABLE);
        assertThat(filters).hasSize(2);
        assertThat(filters.get(0).getExpression()).isEqualTo("tenant_id = 'org_7718'");
        assertThat(filters.get(0).getCatalog()).isEqualTo(Optional.of("lakehouse"));
        assertThat(filters.get(0).getSchema()).isEqualTo(Optional.of("finance"));
    }

    @Test
    void getRowFiltersReturnsEmptyForEmptyList()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/row_filters"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": []}}")));
        assertThat(accessControl.getRowFilters(context("alice", Set.of()), TABLE)).isEmpty();
    }

    @Test
    void getRowFiltersFailsClosedOnInvalidSql()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/row_filters"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": [\"emp_id IN (SELECT id FROM hr.employees)\"]}}")));

        assertThatThrownBy(() -> accessControl.getRowFilters(context("alice", Set.of()), TABLE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getColumnMaskReturnsMaskInViewExpression()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/column_masks"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": \"CASE WHEN true THEN ssn ELSE '***' END\"}}")));

        Optional<ViewExpression> mask = accessControl.getColumnMask(
                context("alice", Set.of()), TABLE, "ssn", BigintType.BIGINT);
        assertThat(mask).isPresent();
        assertThat(mask.get().getExpression()).isEqualTo("CASE WHEN true THEN ssn ELSE '***' END");
    }

    @Test
    void getColumnMaskReturnsEmptyWhenOpaReturnsNull()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/column_masks"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": null}}")));
        assertThat(accessControl.getColumnMask(context("alice", Set.of()), TABLE, "ssn", BigintType.BIGINT))
                .isEmpty();
    }

    @Test
    void getColumnMaskFailsClosedOnInvalidMaskSql()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/column_masks"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": \"(SELECT password FROM hr.users)\"}}")));
        assertThatThrownBy(() -> accessControl.getColumnMask(context("alice", Set.of()), TABLE, "ssn", BigintType.BIGINT))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------
    // Fail-closed modes (§7)
    // ------------------------------------------------------------------

    @Test
    void connectionRefusedFailsClosed()
    {
        int deadPort = opa.port();
        opa.stop();
        OpaAccessControl dead = newAccessControl(deadPort, 250);
        assertThatThrownBy(() -> dead.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("fail closed");
    }

    @Test
    void non200StatusFailsClosed()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> accessControl.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("non-200");
    }

    @Test
    void malformedBodyFailsClosed()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withBody("not-json{")));
        assertThatThrownBy(() -> accessControl.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void opaErrorEnvelopeFailsClosed()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"code\": \"internal_error\", \"message\": \"evaluation failed\"}")));
        assertThatThrownBy(() -> accessControl.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("internal_error");
    }

    @Test
    void timeoutFailsClosed()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withFixedDelay(1000).withBody(
                        "{\"result\": {\"schema_version\": 1, \"result\": true}}")));
        OpaAccessControl slow = newAccessControl(opa.port(), 100);
        assertThatThrownBy(() -> slow.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unsupportedSchemaVersionFailsClosed()
    {
        stubAllow("{\"result\": {\"schema_version\": 99, \"result\": true}}");
        assertThatThrownBy(() -> accessControl.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("schema_version");
    }

    @Test
    void failuresAreNegativeCached()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withStatus(503)));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> accessControl.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of()))
                    .isInstanceOf(AccessDeniedException.class);
        }
        // negative cache (TTL 2s) means only the first attempt reached OPA
        opa.verify(1, postRequestedFor(urlEqualTo("/v1/data/trino/allow")));
    }

    @Test
    void decisionsAreCached()
    {
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": true}}");
        accessControl.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of());
        accessControl.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of());
        opa.verify(1, postRequestedFor(urlEqualTo("/v1/data/trino/allow")));
    }

    @Test
    void differentGroupsProduceSeparateCacheEntries()
    {
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": true}}");
        accessControl.checkCanCreateTable(context("alice", Set.of("engineering")), TABLE, java.util.Map.of());
        accessControl.checkCanCreateTable(context("alice", Set.of("audit")), TABLE, java.util.Map.of());
        // groups are decision-relevant: both calls must have hit OPA (invariant #2)
        opa.verify(2, postRequestedFor(urlEqualTo("/v1/data/trino/allow")));
    }

    @Test
    void negativeCacheExpiryRequeries()
            throws InterruptedException
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .inScenario("flaky")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withBody("{\"result\": {\"schema_version\": 1, \"result\": true}}")));

        assertThatThrownBy(() -> accessControl.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of()))
                .isInstanceOf(AccessDeniedException.class);
        Thread.sleep(2100); // negative TTL is 2s
        accessControl.checkCanCreateTable(context("alice", Set.of()), TABLE, java.util.Map.of());
        opa.verify(2, postRequestedFor(urlEqualTo("/v1/data/trino/allow")));
    }
}
