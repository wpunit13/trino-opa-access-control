package io.github.wpunit13.trino.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.wpunit13.trino.client.OpaFilterDescriptor;
import io.github.wpunit13.trino.client.OpaResponseParser;
import io.github.wpunit13.trino.sql.DescriptorRenderer;
import io.github.wpunit13.trino.sql.SqlExpressionValidator;

import java.util.List;
import java.util.Set;

/**
 * Judges OPA responses for the CLI conformance runner (Milestone 6) using the
 * plugin's AUTHORITATIVE implementation: {@link OpaResponseParser},
 * {@link DescriptorRenderer} and {@link SqlExpressionValidator}. The CLI never
 * re-states the contract in another language — whatever the plugin would reject
 * here is a conformance failure, which is what makes the gate catch
 * plugin/policy version skew.
 *
 * The response handed to {@link #judge} must already be shaped like the OPA
 * server's data-API body (the {@code {"result": ...}} envelope), mirroring what
 * {@code OpaHttpClient} feeds to the parser in the plugin.
 */
public final class ResponseJudge
{
    /** Contract-2 surface per OPA data document (ARCHITECTURE.md §3.2 clause references). */
    public enum Contract
    {
        ALLOW("allow", "§3.2.A"),
        ROW_FILTERS("row_filters", "§3.2.B"),
        COLUMN_MASKS("column_masks", "§3.2.C"),
        FILTER("filter", "§3.2.D");

        public final String name;
        public final String clause;

        Contract(String name, String clause)
        {
            this.name = name;
            this.clause = clause;
        }
    }

    private final OpaResponseParser responseParser;
    private final DescriptorRenderer descriptorRenderer;
    private final SqlExpressionValidator sqlValidator;

    public ResponseJudge(int supportedSchemaVersion, int maxInClauseSize)
    {
        this.responseParser = new OpaResponseParser(supportedSchemaVersion);
        this.descriptorRenderer = new DescriptorRenderer(maxInClauseSize);
        // No function allow-list by default (matches the plugin's default config).
        this.sqlValidator = new SqlExpressionValidator(List.of());
    }

    public record Verdict(boolean conforms, String clause, String detail) {}

    public static Verdict conforming()
    {
        return new Verdict(true, null, null);
    }

    /**
     * @param mode   "passthrough" or "safe" — must match the plugin's opa.sql.mode
     * @param target "catalog.schema.table" of the target resource (from the fixture input)
     * @param column the masked column for COLUMN_MASKS (from the fixture input)
     */
    public Verdict judge(Contract contract, String mode, JsonNode envelope, String target, String column)
    {
        boolean safe = "safe".equalsIgnoreCase(mode);
        try {
            judgeContract(contract, safe, envelope, target, column);
            return conforming();
        }
        catch (RuntimeException e) {
            // OpaResponseException and SqlValidationException are the contract exceptions; anything else
            // (e.g. an unexpected traversal error) must equally fail closed.
            return new Verdict(false, contract.clause, e.getMessage());
        }
    }

    private void judgeContract(Contract contract, boolean safe, JsonNode envelope, String target, String column)
    {
        switch (contract) {
            case ALLOW ->
                    // §3.2.A / §7: undefined rule → deny (parser returns Boolean.FALSE), never an error.
                    responseParser.parseBooleanOrColumnMap(envelope);
            case ROW_FILTERS -> judgeRowFilters(safe, envelope, target);
            case COLUMN_MASKS -> judgeColumnMasks(safe, envelope, target, column);
            case FILTER ->
                    // §3.2.D: undefined/absent result → parser throws (fail closed).
                    responseParser.parseFilterResult(envelope);
        }
    }

    private void judgeRowFilters(boolean safe, JsonNode envelope, String target)
    {
        // §3.2.B: undefined/absent result → parser throws (fail closed, §3.2.D).
        if (safe) {
            for (OpaFilterDescriptor descriptor : responseParser.parseRowFilterDescriptors(envelope)) {
                validateRendered(descriptorRenderer.render(descriptor), target, Set.of());
            }
        }
        else {
            for (String filter : responseParser.parseRowFilters(envelope)) {
                validateRendered(filter, target, Set.of());
            }
        }
    }

    private void judgeColumnMasks(boolean safe, JsonNode envelope, String target, String column)
    {
        // §3.2.C: undefined/absent result → parser throws (fail closed, §3.2.D).
        if (safe) {
            OpaFilterDescriptor descriptor = responseParser.parseColumnMaskDescriptor(envelope);
            if (descriptor != null) {
                validateRendered(descriptorRenderer.render(descriptor), target, Set.of(column));
            }
        }
        else {
            String mask = responseParser.parseColumnMask(envelope);
            if (mask != null) {
                validateRendered(mask, target, Set.of(column));
            }
        }
    }

    /**
     * Mirrors the plugin exactly: rendered/quoted SQL still passes through
     * {@link SqlExpressionValidator} as defense in depth (OpaAccessControl
     * validates every row filter and mask before building a ViewExpression).
     */
    private void validateRendered(String sql, String target, Set<String> allowedColumns)
    {
        sqlValidator.validate(sql, target, allowedColumns);
    }
}
