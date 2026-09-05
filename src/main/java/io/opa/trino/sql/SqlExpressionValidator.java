package io.opa.trino.sql;

import io.trino.sql.parser.ParsingException;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.tree.DefaultTraversalVisitor;
import io.trino.sql.tree.DereferenceExpression;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.FunctionCall;
import io.trino.sql.tree.Identifier;
import io.trino.sql.tree.Node;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Contract 4 (ARCHITECTURE.md §3.4), addressing review.md §3.2:
 * structurally validates OPA-emitted SQL before it is ever wrapped in a ViewExpression.
 *
 * An expression is rejected (fail closed) when:
 *  - it does not parse as a single Trino expression,
 *  - it references a table other than the target resource (e.g. via a subquery),
 *  - it references a column outside the allowed column set (when one is enforced),
 *  - it calls a function outside the configured allow-list (when one is configured).
 */
public final class SqlExpressionValidator
{
    private final SqlParser sqlParser = new SqlParser();
    private final Set<String> allowedFunctions;

    public SqlExpressionValidator(List<String> allowedFunctions)
    {
        Set<String> lowered = new LinkedHashSet<>();
        for (String function : allowedFunctions) {
            String trimmed = function.trim();
            if (!trimmed.isEmpty()) {
                lowered.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        this.allowedFunctions = lowered;
    }

    /**
     * @param targetCatalogSchemaTable "catalog.schema.table" of the target resource
     * @param allowedColumns columns the expression may reference; empty = unrestricted
     * @throws SqlValidationException if the expression is invalid (fail closed)
     */
    public void validate(String expression, String targetCatalogSchemaTable, Set<String> allowedColumns)
    {
        if (expression == null || expression.isBlank()) {
            throw new SqlValidationException("OPA-emitted SQL expression is empty");
        }
        Parsed parsed;
        try {
            parsed = parse(expression);
        }
        catch (ParsingException e) {
            throw new SqlValidationException("OPA-emitted SQL does not parse: " + e.getMessage(), e);
        }

        Set<String> columns = new LinkedHashSet<>();
        for (String column : allowedColumns == null ? Set.<String>of() : allowedColumns) {
            columns.add(column.toLowerCase(Locale.ROOT));
        }

        Visitor visitor = new Visitor(targetCatalogSchemaTable.toLowerCase(Locale.ROOT), columns);
        visitor.process(parsed.expression, null);
        if (visitor.error != null) {
            throw new SqlValidationException(visitor.error);
        }
    }

    private record Parsed(Expression expression) {}

    // SqlParser.createExpression is an instance method in this Trino version.
    private Parsed parse(String expression)
            throws ParsingException
    {
        Expression tree = sqlParser.createExpression(expression);
        return new Parsed(tree);
    }

    private final class Visitor
            extends DefaultTraversalVisitor<Void>
    {
        private final String targetTable;
        private final Set<String> allowedColumns;
        private final Set<String> aliasBases = new LinkedHashSet<>();
        private String error;

        private Visitor(String targetTable, Set<String> allowedColumns)
        {
            this.targetTable = targetTable;
            this.allowedColumns = allowedColumns;
        }

        @Override
        protected Void visitFunctionCall(FunctionCall node, Void context)
        {
            String name = node.getName().toString().toLowerCase(Locale.ROOT);
            if (!allowedFunctions.isEmpty() && !allowedFunctions.contains(name)) {
                if (error == null) {
                    error = "OPA-emitted SQL calls a function outside the allow-list: " + name;
                }
                return null;
            }
            return super.visitFunctionCall(node, context);
        }

        @Override
        protected Void visitDereferenceExpression(DereferenceExpression node, Void context)
        {
            // Column references qualified as <base>.<field>: the field must be an
            // allowed column. The base (a table alias such as "salaries" in
            // "salaries.tenant_id") is exempted from the bare-identifier check.
            Optional<Identifier> fieldIdentifier = node.getField();
            String fieldName = fieldIdentifier.map(Identifier::getValue).orElse(null);
            if (fieldName != null && !allowedColumns.isEmpty() && !allowedColumns.contains(fieldName.toLowerCase(Locale.ROOT))) {
                if (error == null) {
                    error = "OPA-emitted SQL references a column outside the target resource: " + fieldName;
                }
            }
            if (node.getBase() instanceof Identifier base) {
                aliasBases.add(base.getValue().toLowerCase(Locale.ROOT));
            }
            return super.visitDereferenceExpression(node, context);
        }

        @Override
        protected Void visitIdentifier(Identifier node, Void context)
        {
            // Bare identifiers are column references (function names live in
            // QualifiedName objects, which are not visited as Identifiers).
            // Reject anything outside the target resource's allowed columns.
            String value = node.getValue().toLowerCase(Locale.ROOT);
            if (!allowedColumns.isEmpty() && !aliasBases.contains(value) && !allowedColumns.contains(value)) {
                if (error == null) {
                    error = "OPA-emitted SQL references a column outside the target resource: " + node.getValue();
                }
            }
            return super.visitIdentifier(node, context);
        }

        @Override
        protected Void visitTable(io.trino.sql.tree.Table node, Void context)
        {
            // The reference may be unqualified ("salaries") or qualified
            // ("finance.salaries", "lakehouse.finance.salaries"): accept any
            // suffix match of the target table; anything else fails closed.
            String name = node.getName().toString().toLowerCase(Locale.ROOT);
            String target = targetTable;
            boolean allowed = name.equals(target) || target.endsWith("." + name) || name.endsWith("." + target);
            if (!allowed) {
                if (error == null) {
                    error = "OPA-emitted SQL references a table outside the target resource: " + name;
                }
            }
            return super.visitTable(node, context);
        }
    }
}
