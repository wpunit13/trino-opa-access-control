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
import io.opa.trino.sql.SqlExpressionValidator;
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
 * §3.2.D: filter methods (bulk evaluation, one OPA call per invocation).
 * Semantics: empty result = allow nothing; absent result = error → fail closed.
 */
class OpaFilterMethodsIntegrationTest
{
    private static final CatalogSchemaTableName TABLE = new CatalogSchemaTableName(
            "lakehouse", new SchemaTableName("finance", "salaries"));

    private WireMockServer opa;
    private OpaAccessControl accessControl;

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
        accessControl = newAccessControl(opa.port());
    }

    @AfterEach
    void tearDown()
    {
        opa.stop();
    }

    private static OpaAccessControl newAccessControl(int port)
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
                new DecisionCache(true, 10_000, 30, 2, List.of("source_ip", "catalog_session_properties")),
                new SqlExpressionValidator(List.of()));
    }

    private void stubFilter(String body)
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/filter"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void filterCatalogsReturnsAllowListedSubset()
    {
        stubFilter("{\"result\": {\"schema_version\": 1, \"result\": [\"lakehouse\", \"archive\"]}}");

        Set<String> allowed = accessControl.filterCatalogs(context(), Set.of("lakehouse", "archive", "secret"));
        assertThat(allowed).containsExactlyInAnyOrder("lakehouse", "archive");
    }

    @Test
    void filterSchemasReturnsSubset()
    {
        stubFilter("{\"result\": {\"schema_version\": 1, \"result\": [\"finance\"]}}");

        Set<String> allowed = accessControl.filterSchemas(context(), "lakehouse", Set.of("finance", "hr", "pii"));
        assertThat(allowed).containsExactly("finance");
    }

    @Test
    void filterTablesRoundTripsSchemaTableNames()
    {
        stubFilter("{\"result\": {\"schema_version\": 1, \"result\": [\"finance.salaries\", \"finance.budget\"]}}");

        Set<SchemaTableName> allowed = accessControl.filterTables(
                context(), "lakehouse",
                Set.of(new SchemaTableName("finance", "salaries"),
                        new SchemaTableName("finance", "budget"),
                        new SchemaTableName("hr", "employees")));
        assertThat(allowed).containsExactlyInAnyOrder(
                new SchemaTableName("finance", "salaries"),
                new SchemaTableName("finance", "budget"));
    }

    @Test
    void filterColumnsReturnsSubset()
    {
        stubFilter("{\"result\": {\"schema_version\": 1, \"result\": [\"name\"]}}");

        Set<String> allowed = accessControl.filterColumns(context(), TABLE, Set.of("name", "ssn", "salary"));
        assertThat(allowed).containsExactly("name");
    }

    @Test
    void emptyResultMeansAllowNothing()
    {
        stubFilter("{\"result\": {\"schema_version\": 1, \"result\": []}}");
        assertThat(accessControl.filterCatalogs(context(), Set.of("lakehouse", "archive"))).isEmpty();
        assertThat(accessControl.filterSchemas(context(), "lakehouse", Set.of("finance"))).isEmpty();
        assertThat(accessControl.filterTables(context(), "lakehouse", Set.of(new SchemaTableName("finance", "salaries")))).isEmpty();
        assertThat(accessControl.filterColumns(context(), TABLE, Set.of("ssn"))).isEmpty();
    }

    @Test
    void absentResultFailsClosed()
    {
        stubFilter("{\"result\": {\"schema_version\": 1}}");
        assertThatThrownBy(() -> accessControl.filterCatalogs(context(), Set.of("lakehouse")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("fail closed");
        assertThatThrownBy(() -> accessControl.filterSchemas(context(), "lakehouse", Set.of("finance")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accessControl.filterTables(context(), "lakehouse", Set.of(new SchemaTableName("finance", "salaries"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accessControl.filterColumns(context(), TABLE, Set.of("ssn")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void bulkEvaluationIssuesSingleOpaCallPerInvocation()
    {
        stubFilter("{\"result\": {\"schema_version\": 1, \"result\": [\"finance\"]}}");
        accessControl.filterSchemas(context(), "lakehouse", Set.of("finance", "hr", "pii", "sales", "ops"));
        // exactly ONE request despite 5 candidates
        opa.verify(1, postRequestedFor(urlEqualTo("/v1/data/trino/filter")));
    }

    @Test
    void candidatesAreMarshaledIntoRequest()
    {
        stubFilter("{\"result\": {\"schema_version\": 1, \"result\": []}}");
        accessControl.filterSchemas(context(), "lakehouse", Set.of("hr", "finance"));

        var requests = opa.findAll(postRequestedFor(urlEqualTo("/v1/data/trino/filter")));
        assertThat(requests).hasSize(1);
        String body = requests.get(0).getBodyAsString();
        assertThat(body).contains("\"action\":\"FILTER_SCHEMAS\"");
        assertThat(body).contains("\"catalog\":\"lakehouse\"");
        // candidates ride in resource.columns, sorted
        assertThat(body).contains("\"columns\":[\"finance\",\"hr\"]");
    }

    @Test
    void non200StatusFailsClosedForFilters()
    {
        opa.stubFor(post(urlEqualTo("/v1/data/trino/filter"))
                .willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> accessControl.filterCatalogs(context(), Set.of("lakehouse")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void filterDecisionsAreCached()
    {
        stubFilter("{\"result\": {\"schema_version\": 1, \"result\": [\"lakehouse\"]}}");
        accessControl.filterCatalogs(context(), Set.of("lakehouse", "archive"));
        accessControl.filterCatalogs(context(), Set.of("lakehouse", "archive"));
        opa.verify(1, postRequestedFor(urlEqualTo("/v1/data/trino/filter")));
    }

    @Test
    void differentCandidatesProduceSeparateCacheEntries()
    {
        stubFilter("{\"result\": {\"schema_version\": 1, \"result\": [\"lakehouse\"]}}");
        accessControl.filterCatalogs(context(), Set.of("lakehouse", "archive"));
        accessControl.filterCatalogs(context(), Set.of("lakehouse", "other"));
        // candidates are decision-relevant (part of the canonical cache key)
        opa.verify(2, postRequestedFor(urlEqualTo("/v1/data/trino/filter")));
    }
}
