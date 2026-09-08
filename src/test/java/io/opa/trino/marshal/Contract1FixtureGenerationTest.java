package io.opa.trino.marshal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 5: generates the Contract-1 input fixtures for the policy conformance
 * kit (policy-conformance-kit/fixtures/inputs/*.json) from the REAL marshaller, so
 * the kit's inputs can never drift from what the plugin actually sends.
 *
 * Runs on every `mvn test`; regeneration is idempotent.
 */
class Contract1FixtureGenerationTest
{
    private static final String FIXED_DECISION_ID = "00000000-0000-0000-0000-000000000000";
    private static final Path OUT = Path.of("policy-conformance-kit", "fixtures", "contract1_inputs.json");

    private final OpaRequestMarshaller marshaller = new OpaRequestMarshaller();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private OpaRequestContext context(OpaAction action, String user, java.util.Set<String> groups,
                                      String catalog, String schema, String table, List<String> columns)
    {
        Map<String, List<String>> roles = new LinkedHashMap<>();
        roles.put("system", List.of("analyst"));
        return new OpaRequestContext(
                action,
                user,
                List.copyOf(groups),
                roles,
                List.of(),
                Optional.of("10.0.0.5"),
                Optional.of("20260905_001234_00001_abcde"),
                Optional.empty(),
                Map.of(),
                catalog,
                schema,
                table,
                columns);
    }

    @Test
    void generateContract1InputFixtures() throws Exception
    {
        Files.createDirectories(OUT.getParent());

        // OPA merges loaded JSON at the data root, so all fixtures live under a
        // single namespaced document: data.inputs.<name>.
        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("create_table_alice", input(OpaAction.CREATE_TABLE, "alice",
                java.util.Set.of("legal_entity_reader_eu"), "lakehouse", "finance", "salaries", null));
        fixtures.put("select_columns_alice", input(OpaAction.SELECT_FROM_COLUMNS, "alice",
                java.util.Set.of("legal_entity_reader_eu"), "lakehouse", "finance", "salaries",
                List.of("name", "salary", "ssn")));
        fixtures.put("create_table_nobody", input(OpaAction.CREATE_TABLE, "nobody",
                java.util.Set.of(), "lakehouse", "finance", "salaries", null));
        fixtures.put("row_filters_alice", input(OpaAction.GET_ROW_FILTERS, "alice",
                java.util.Set.of("legal_entity_reader_eu"), "lakehouse", "finance", "salaries", null));
        fixtures.put("row_filters_nobody", input(OpaAction.GET_ROW_FILTERS, "nobody",
                java.util.Set.of(), "lakehouse", "finance", "salaries", null));
        fixtures.put("column_masks_ssn", input(OpaAction.GET_COLUMN_MASKS, "alice",
                java.util.Set.of("legal_entity_reader_eu"), "lakehouse", "finance", "customers",
                List.of("ssn")));
        fixtures.put("filter_schemas", input(OpaAction.FILTER_SCHEMAS, "alice",
                java.util.Set.of("legal_entity_reader_eu"), "lakehouse", null, null,
                List.of("finance", "hr", "pii")));

        Files.writeString(OUT, mapper.writeValueAsString(Map.of("inputs", fixtures)) + "\n");
        assertThat(fixtures).hasSize(7);
    }

    private Map<String, Object> input(OpaAction action, String user, java.util.Set<String> groups,
                                      String catalog, String schema, String table, List<String> columns)
    {
        return marshaller.marshal(context(action, user, groups, catalog, schema, table, columns), FIXED_DECISION_ID);
    }
}
