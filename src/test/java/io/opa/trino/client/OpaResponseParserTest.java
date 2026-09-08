package io.opa.trino.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract 2 (§3.2) + Contract 5 (§3.5): response unmarshaling and schema validation. */
class OpaResponseParserTest
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpaResponseParser parser = new OpaResponseParser(1);

    private com.fasterxml.jackson.databind.JsonNode json(String s)
    {
        try {
            return mapper.readTree(s);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- Authorization checks ----

    @Test
    void parsesAllowTrue()
    {
        assertThat(parser.parseBoolean(json("{\"result\": {\"schema_version\": 1, \"result\": true}}"))).isTrue();
    }

    @Test
    void parsesAllowFalse()
    {
        assertThat(parser.parseBoolean(json("{\"result\": {\"schema_version\": 1, \"result\": false}}"))).isFalse();
    }

    @Test
    void undefinedAllowRuleDenies()
    {
        // OPA omits 'result' when the rule is undefined → default deny, not error
        assertThat(parser.parseBoolean(json("{}"))).isFalse();
        assertThat(parser.parseBoolean(json("{\"result\": null}"))).isFalse();
    }

    @Test
    void acceptsContractShapeAtTopLevel()
    {
        assertThat(parser.parseBoolean(json("{\"schema_version\": 1, \"result\": true}"))).isTrue();
    }

    // ---- Row filters ----

    @Test
    void parsesRowFilters()
    {
        var filters = parser.parseRowFilters(json(
                "{\"result\": {\"schema_version\": 1, \"result\": [\"a = 'x'\", \"b IN ('y','z')\"]}}"));
        assertThat(filters).containsExactly("a = 'x'", "b IN ('y','z')");
    }

    @Test
    void parsesEmptyRowFiltersAsNoFilter()
    {
        assertThat(parser.parseRowFilters(json("{\"result\": {\"schema_version\": 1, \"result\": []}}"))).isEmpty();
    }

    // ---- Column masks ----

    @Test
    void parsesMaskExpression()
    {
        String mask = parser.parseColumnMask(json(
                "{\"result\": {\"schema_version\": 1, \"result\": \"CASE WHEN true THEN ssn ELSE '***' END\"}}"));
        assertThat(mask).isEqualTo("CASE WHEN true THEN ssn ELSE '***' END");
    }

    @Test
    void parsesNullMaskAsUnmasked()
    {
        assertThat(parser.parseColumnMask(json("{\"result\": {\"schema_version\": 1, \"result\": null}}"))).isNull();
    }

    // ---- Per-column allow/deny map (§5) ----

    @Test
    void parsesPerColumnDecisionMap()
    {
        Object decision = parser.parseBooleanOrColumnMap(json(
                "{\"result\": {\"schema_version\": 1, \"result\": {\"ssn\": true, \"salary\": false}}}"));
        assertThat(decision).isEqualTo(java.util.Map.of("ssn", true, "salary", false));
    }

    @Test
    void perColumnMapWithNonBooleanFailsClosed()
    {
        assertThatThrownBy(() -> parser.parseBooleanOrColumnMap(json(
                "{\"result\": {\"schema_version\": 1, \"result\": {\"ssn\": \"yes\"}}}")))
                .isInstanceOf(OpaResponseException.class);
    }

    // ---- Fail-closed shapes ----

    @Test
    void missingSchemaVersionFailsClosed()
    {
        assertThatThrownBy(() -> parser.parseBoolean(json("{\"result\": true}")))
                .isInstanceOf(OpaResponseException.class);
    }

    @Test
    void unsupportedSchemaVersionFailsClosed()
    {
        assertThatThrownBy(() -> parser.parseBoolean(json("{\"result\": {\"schema_version\": 2, \"result\": true}}")))
                .isInstanceOf(OpaResponseException.class)
                .hasMessageContaining("schema_version");
    }

    @Test
    void nonBooleanAllowResultFailsClosed()
    {
        assertThatThrownBy(() -> parser.parseBoolean(json("{\"result\": {\"schema_version\": 1, \"result\": \"yes\"}}")))
                .isInstanceOf(OpaResponseException.class);
    }

    @Test
    void missingRowFiltersResultFailsClosed()
    {
        assertThatThrownBy(() -> parser.parseRowFilters(json("{\"result\": {\"schema_version\": 1}}")))
                .isInstanceOf(OpaResponseException.class);
    }

    @Test
    void nonStringRowFilterEntryFailsClosed()
    {
        assertThatThrownBy(() -> parser.parseRowFilters(json("{\"result\": {\"schema_version\": 1, \"result\": [42]}}")))
                .isInstanceOf(OpaResponseException.class);
    }

    @Test
    void nonStringMaskFailsClosed()
    {
        assertThatThrownBy(() -> parser.parseColumnMask(json("{\"result\": {\"schema_version\": 1, \"result\": 7}}")))
                .isInstanceOf(OpaResponseException.class);
    }

    @Test
    void nonObjectResponseFailsClosed()
    {
        assertThatThrownBy(() -> parser.parseBoolean(json("[]")))
                .isInstanceOf(OpaResponseException.class);
    }
}
