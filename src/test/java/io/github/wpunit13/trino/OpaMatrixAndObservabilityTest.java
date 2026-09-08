package io.github.wpunit13.trino;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.wpunit13.trino.cache.CacheKeyCalculator;
import io.github.wpunit13.trino.cache.DecisionCache;
import io.github.wpunit13.trino.client.CircuitBreaker;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milestone 3: explicit default-deny, per-column allow/deny support, and
 * decision_id correlation (log + request) plus Micrometer metrics (§8.4).
 */
class OpaMatrixAndObservabilityTest
{
    private static final CatalogSchemaTableName TABLE = new CatalogSchemaTableName(
            "lakehouse", new SchemaTableName("finance", "salaries"));

    private WireMockServer opa;
    private OpaAccessControl accessControl;
    private OpaMetrics metrics;
    private final List<String> decisionLog = new ArrayList<>();

    private static SystemSecurityContext context()
    {
        Identity identity = Identity.forUser("alice")
                .withGroups(Set.of("engineering"))
                .withEnabledRoles(Set.of("analyst"))
                .build();
        return new SystemSecurityContext(identity, QueryId.valueOf("20260905_001234_00001_abcde"), Instant.now());
    }

    @BeforeEach
    void setUp()
    {
        opa = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        opa.start();
        metrics = OpaMetrics.createDefault();
        accessControl = newAccessControl(opa.port());
    }

    @AfterEach
    void tearDown()
    {
        opa.stop();
    }

    private OpaAccessControl newAccessControl(int port)
    {
        OpaConfig config = new OpaConfig();
        config.setEndpointUrl("http://127.0.0.1:" + port);
        config.setTimeoutMs(250);
        config.setRetryMax(0);
        config.validate();
        return new OpaAccessControl(
                config,
                new OpaHttpClient(config.getEndpointUrl(), 250, 0, 0, new CircuitBreaker(10, 10_000)),
                new OpaResponseParser(OpaConfig.SUPPORTED_SCHEMA_VERSION),
                new OpaRequestMarshaller(),
                new CacheKeyCalculator(),
                new DecisionCache(false, 10_000, 30, 2, List.of()), // cache off: observe every call
                new SqlExpressionValidator(List.of()),
                metrics,
                decisionLog::add);
    }

    private void stubAllow(String body)
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/allow"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    // ------------------------------------------------------------------
    // Explicit default deny
    // ------------------------------------------------------------------

    @Test
    void unmappedMethodsDefaultDenyWithoutCallingOpa()
    {
        assertThatThrownBy(() -> accessControl.checkCanInsertIntoTable(context(), TABLE))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("default deny");
        assertThatThrownBy(() -> accessControl.checkCanDeleteFromTable(context(), TABLE))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accessControl.checkCanDenyTablePrivilege(
                context(), io.trino.spi.security.Privilege.SELECT, TABLE,
                new io.trino.spi.security.TrinoPrincipal(io.trino.spi.security.PrincipalType.USER, "bob")))
                .isInstanceOf(AccessDeniedException.class);
        // NO OPA traffic for default-denied methods
        opa.verify(0, postRequestedFor(urlEqualTo("/v1/data/trino/allow")));
        // ...but the denial is audited with a decision_id
        assertThat(decisionLog).anySatisfy(line -> {
            assertThat(line).contains("result=default_deny");
            assertThat(line).contains("checkCanInsertIntoTable");
            assertThat(line).containsPattern("decision_id=[0-9a-f\\-]{36}");
        });
    }

    @Test
    void booleanReturningUnmappedMethodDefaultsToDeny()
    {
        // canExecuteFunction's SPI default is ALLOW — our override must deny.
        assertThat(accessControl.canExecuteFunction(context(),
                new io.trino.spi.connector.CatalogSchemaRoutineName("lakehouse", "finance", "risk_fn"))).isFalse();
    }

    // ------------------------------------------------------------------
    // Per-column allow/deny map (§5 note)
    // ------------------------------------------------------------------

    @Test
    void perColumnMapDeniesWhenAnyRequestedColumnIsDenied()
    {
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": {\"ssn\": true, \"salary\": false}}}");
        assertThatThrownBy(() -> accessControl.checkCanSelectFromColumns(context(), TABLE, Set.of("ssn", "salary")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("salary");
    }

    @Test
    void perColumnMapAllowsWhenAllRequestedColumnsAllowed()
    {
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": {\"ssn\": true, \"salary\": true}}}");
        accessControl.checkCanSelectFromColumns(context(), TABLE, Set.of("ssn", "salary"));
    }

    @Test
    void perColumnMapDeniesColumnsMissingFromMap()
    {
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": {\"ssn\": true}}}");
        assertThatThrownBy(() -> accessControl.checkCanSelectFromColumns(context(), TABLE, Set.of("ssn", "bonus")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("bonus");
    }

    @Test
    void booleanResponseStillSupportedForSelectFromColumns()
    {
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": true}}");
        accessControl.checkCanSelectFromColumns(context(), TABLE, Set.of("ssn", "salary"));
    }

    @Test
    void columnsAreMarshaledSortedForStableKeys()
    {
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": true}}");
        accessControl.checkCanSelectFromColumns(context(), TABLE, Set.of("zeta", "alpha", "mid"));
        var requests = opa.findAll(postRequestedFor(urlEqualTo("/v1/data/trino/allow")));
        assertThat(requests.get(0).getBodyAsString())
                .contains("\"action\":\"SELECT_FROM_COLUMNS\"")
                .contains("\"columns\":[\"alpha\",\"mid\",\"zeta\"]");
    }

    // ------------------------------------------------------------------
    // decision_id correlation (§8.4)
    // ------------------------------------------------------------------

    @Test
    void decisionIdIsStableAcrossOneSpiCallAndLogged()
    {
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": true}}");
        accessControl.checkCanCreateTable(context(), TABLE, Map.of());

        String requestDecisionId = extractDecisionId(
                opa.findAll(postRequestedFor(urlEqualTo("/v1/data/trino/allow"))).get(0).getBodyAsString());
        String logDecisionId = extractDecisionId(decisionLog.get(0));
        // the decision_id sent to OPA is exactly the one audited for that call
        assertThat(logDecisionId).isEqualTo(requestDecisionId);

        // a second call gets a different decision_id
        decisionLog.clear();
        accessControl.checkCanCreateTable(context(), TABLE, Map.of());
        String secondId = extractDecisionId(
                opa.findAll(postRequestedFor(urlEqualTo("/v1/data/trino/allow"))).get(1).getBodyAsString());
        assertThat(secondId).isNotEqualTo(requestDecisionId);
    }

    private static String extractDecisionId(String body)
    {
        java.util.regex.Matcher jsonMatch = java.util.regex.Pattern.compile("\"decision_id\":\"([0-9a-f\\-]{36})\"").matcher(body);
        if (jsonMatch.find()) {
            return jsonMatch.group(1);
        }
        java.util.regex.Matcher logMatch = java.util.regex.Pattern.compile("decision_id=([0-9a-f\\-]{36})").matcher(body);
        if (logMatch.find()) {
            return logMatch.group(1);
        }
        throw new AssertionError("no decision_id in: " + body);
    }

    // ------------------------------------------------------------------
    // Metrics (§8.4)
    // ------------------------------------------------------------------

    @Test
    void metricsRecordAllowDenyFailClosedAndLatency()
    {
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": true}}");
        accessControl.checkCanCreateTable(context(), TABLE, Map.of()); // allow

        opa.resetRequests();
        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": false}}");
        assertThatThrownBy(() -> accessControl.checkCanCreateTable(context(), TABLE, Map.of()))
                .isInstanceOf(AccessDeniedException.class); // deny (from OPA, not fail-closed)

        stubAllow("{\"result\": {\"schema_version\": 1, \"result\": {\"schema_version\": 99, \"result\": true}}}");
        assertThatThrownBy(() -> accessControl.checkCanCreateTable(context(), TABLE, Map.of()))
                .isInstanceOf(AccessDeniedException.class); // fail closed (bad schema_version)

        var decisions = metrics.registry().get("opa.decisions").counters();
        double allow = decisions.stream()
                .filter(c -> "allow".equals(c.getId().getTag("outcome")))
                .mapToDouble(c -> c.count()).sum();
        double deny = decisions.stream()
                .filter(c -> "deny".equals(c.getId().getTag("outcome")))
                .mapToDouble(c -> c.count()).sum();
        assertThat(allow).isEqualTo(1.0);
        assertThat(deny).isEqualTo(1.0);

        assertThat(metrics.registry().get("opa.fail.closed").counter().count()).isEqualTo(1.0);
        assertThat(metrics.registry().get("opa.decision.latency").timers().size()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.registry().get("opa.errors").counters().size()).isEqualTo(1);
    }
}
