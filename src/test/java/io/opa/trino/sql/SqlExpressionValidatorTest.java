package io.opa.trino.sql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract 4 (ARCHITECTURE.md §3.4), addressing review.md §3.2:
 * OPA-emitted SQL must be structurally validated; anything referencing resources
 * outside the target fails closed.
 */
class SqlExpressionValidatorTest
{
    private static final String TARGET = "lakehouse.finance.salaries";
    private final SqlExpressionValidator validator = new SqlExpressionValidator(List.of());

    @Test
    void acceptsSimplePredicateOnTargetTable()
    {
        assertThatCode(() -> validator.validate("tenant_id = 'org_7718'", TARGET, Set.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsInListPredicate()
    {
        assertThatCode(() -> validator.validate("org_hierarchy_id IN ('dept_eng_01', 'dept_eng_core')", TARGET, Set.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsCaseExpressionWithSubqueryOnTargetTable()
    {
        assertThatCode(() -> validator.validate(
                "CASE WHEN 'security_admin' IN (SELECT role FROM salaries) THEN ssn ELSE '***' END",
                TARGET, Set.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnparseableSql()
    {
        assertThatThrownBy(() -> validator.validate("tenant_id = = 'x' AND (", TARGET, Set.of()))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("does not parse");
    }

    @Test
    void rejectsBlankSql()
    {
        assertThatThrownBy(() -> validator.validate("   ", TARGET, Set.of()))
                .isInstanceOf(SqlValidationException.class);
    }

    @Test
    void rejectsSubqueryReferencingAnotherTable()
    {
        assertThatThrownBy(() -> validator.validate(
                "emp_id IN (SELECT id FROM hr.employees)",
                TARGET, Set.of()))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("outside the target resource");
    }

    @Test
    void rejectsColumnOutsideAllowedSet()
    {
        assertThatThrownBy(() -> validator.validate("salary = 10", TARGET, Set.of("tenant_id")))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("outside the target resource");
    }

    @Test
    void rejectsQualifiedColumnOutsideAllowedSet()
    {
        assertThatThrownBy(() -> validator.validate("salaries.salary = 10", TARGET, Set.of("tenant_id")))
                .isInstanceOf(SqlValidationException.class);
    }

    @Test
    void rejectsFunctionOutsideAllowList()
    {
        SqlExpressionValidator restricted = new SqlExpressionValidator(List.of("current_role", "substr"));
        assertThatCode(() -> restricted.validate("SUBSTR(tenant_id, 1, 3) = 'dep'", TARGET, Set.of()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> restricted.validate("md5(tenant_id) = 'x'", TARGET, Set.of()))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("allow-list");
    }
}
