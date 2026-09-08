package io.github.wpunit13.trino.marshal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract 1 (ARCHITECTURE.md §3.1): exact marshaled input shape. */
class OpaRequestMarshallerTest
{
    private final OpaRequestMarshaller marshaller = new OpaRequestMarshaller();
    private final ObjectMapper mapper = new ObjectMapper();

    private OpaRequestContext sampleContext()
    {
        Map<String, List<String>> roles = new LinkedHashMap<>();
        roles.put("system", List.of("analyst"));
        roles.put("lakehouse", List.of("developer"));
        return new OpaRequestContext(
                OpaAction.GET_ROW_FILTERS,
                "alice",
                List.of("engineering", "analytics_leads"),
                roles,
                List.of("env:production", "dept:core_platform"),
                Optional.of("10.0.12.45"),
                Optional.of("20260905_001234_00001_abcde"),
                Optional.of("SELECT"),
                Map.of("lakehouse.some_property", "value"),
                "lakehouse",
                "finance",
                "salaries",
                null);
    }

    @Test
    void marshalsExactContractShape()
            throws Exception
    {
        Map<String, Object> input = marshaller.marshal(sampleContext(), "3f2b1c9e-7a4d-4e5f-9b1a-2c3d4e5f6a7b");
        String json = mapper.writeValueAsString(input);

        com.fasterxml.jackson.databind.JsonNode tree = mapper.readTree(json);
        assertThat(tree.get("schema_version").asInt()).isEqualTo(1);
        assertThat(tree.get("action").asText()).isEqualTo("GET_ROW_FILTERS");
        assertThat(tree.get("decision_id").asText()).isEqualTo("3f2b1c9e-7a4d-4e5f-9b1a-2c3d4e5f6a7b");

        var identity = tree.get("identity");
        assertThat(identity.get("user").asText()).isEqualTo("alice");
        assertThat(identity.get("groups")).extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .containsExactly("analytics_leads", "engineering");
        assertThat(identity.get("roles").get("system")).extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .containsExactly("analyst");
        assertThat(identity.get("roles").get("lakehouse")).extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .containsExactly("developer");
        assertThat(identity.get("client_tags")).extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .containsExactly("dept:core_platform", "env:production");
        assertThat(identity.get("source_ip").asText()).isEqualTo("10.0.12.45");

        var resource = tree.get("resource");
        assertThat(resource.get("catalog").asText()).isEqualTo("lakehouse");
        assertThat(resource.get("schema").asText()).isEqualTo("finance");
        assertThat(resource.get("table").asText()).isEqualTo("salaries");
        assertThat(resource.get("columns").isNull()).isTrue();

        var session = tree.get("session");
        assertThat(session.get("query_id").asText()).isEqualTo("20260905_001234_00001_abcde");
        assertThat(session.get("query_type").asText()).isEqualTo("SELECT");
        assertThat(session.get("catalog_session_properties").get("lakehouse.some_property").asText()).isEqualTo("value");
    }

    @Test
    void marshalsColumnsForColumnScopedOperations()
    {
        OpaRequestContext ctx = new OpaRequestContext(
                OpaAction.SELECT_FROM_COLUMNS, "bob", List.of(), Map.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of(),
                "lakehouse", "finance", "salaries",
                List.of("zeta", "alpha"));
        Map<String, Object> input = marshaller.marshal(ctx, "id");
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) ((Map<String, Object>) input.get("resource")).get("columns");
        // columns are sorted for stable canonical serialization
        assertThat(columns).containsExactly("alpha", "zeta");
    }

    @Test
    void generatesUuidDecisionIds()
    {
        assertThat(marshaller.newDecisionId()).isNotEqualTo(marshaller.newDecisionId());
    }
}
