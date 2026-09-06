package io.opa.trino.sql;

import io.opa.trino.client.OpaFilterDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Safe mode (§3.4 mode 2): the plugin owns quoting/escaping; no raw-string path exists. */
class DescriptorRendererTest
{
    private final DescriptorRenderer renderer = new DescriptorRenderer(1000);

    @Test
    void rendersInClause()
    {
        String sql = renderer.render(new OpaFilterDescriptor("in", "org_unit_id",
                List.of("dept_eng_01", "dept_eng_core")));
        assertThat(sql).isEqualTo("org_unit_id IN ('dept_eng_01', 'dept_eng_core')");
    }

    @Test
    void escapesSingleQuotesInValues()
    {
        String sql = renderer.render(new OpaFilterDescriptor("eq", "legal_entity",
                List.of("O'Brien Enterprises")));
        assertThat(sql).isEqualTo("legal_entity = 'O''Brien Enterprises'");
    }

    @Test
    void escapesSingleQuotesInsideInClause()
    {
        String sql = renderer.render(new OpaFilterDescriptor("in", "country_iso", List.of("DE", "Cote d'Ivoire")));
        assertThat(sql).isEqualTo("country_iso IN ('DE', 'Cote d''Ivoire')");
    }

    @Test
    void rendersNumbersAndBooleansAsLiterals()
    {
        assertThat(renderer.render(new OpaFilterDescriptor("eq", "reporting_year", List.of(2026))))
                .isEqualTo("reporting_year = 2026");
        assertThat(renderer.render(new OpaFilterDescriptor("eq", "is_active", List.of(true))))
                .isEqualTo("is_active = true");
    }

    @Test
    void rendersNullPredicates()
    {
        assertThat(renderer.render(new OpaFilterDescriptor("is_null", "parent_id", List.of())))
                .isEqualTo("parent_id IS NULL");
        assertThat(renderer.render(new OpaFilterDescriptor("is_not_null", "parent_id", List.of())))
                .isEqualTo("parent_id IS NOT NULL");
    }

    @Test
    void emptyInListRendersFalse()
    {
        assertThat(renderer.render(new OpaFilterDescriptor("in", "org_unit_id", List.of())))
                .isEqualTo("false");
    }

    @Test
    void boundsInClauseSize()
    {
        DescriptorRenderer bounded = new DescriptorRenderer(3);
        assertThatThrownBy(() -> bounded.render(new OpaFilterDescriptor("in", "org_unit_id",
                List.of("a", "b", "c", "d"))))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("max-in-clause-size");
    }

    @Test
    void rejectsUnsafeIdentifiers()
    {
        assertThatThrownBy(() -> renderer.render(new OpaFilterDescriptor("eq", "col; DROP TABLE x", List.of("v"))))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("safe identifier");
        assertThatThrownBy(() -> renderer.render(new OpaFilterDescriptor("eq", "1=1 OR \"x\"", List.of("v"))))
                .isInstanceOf(SqlValidationException.class);
        assertThatThrownBy(() -> renderer.render(new OpaFilterDescriptor("eq", "\"password\"", List.of("v"))))
                .isInstanceOf(SqlValidationException.class);
    }

    @Test
    void rejectsWrongArityForScalarOps()
    {
        assertThatThrownBy(() -> renderer.render(new OpaFilterDescriptor("eq", "country_iso", List.of())))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("exactly one value");
        assertThatThrownBy(() -> renderer.render(new OpaFilterDescriptor("neq", "country_iso", List.of("DE", "FR"))))
                .isInstanceOf(SqlValidationException.class);
    }

    @Test
    void rejectsUnsupportedOp()
    {
        assertThatThrownBy(() -> renderer.render(new OpaFilterDescriptor("like", "country_iso", List.of("DE%"))))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("Unsupported descriptor op");
    }
}
