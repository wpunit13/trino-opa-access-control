package io.opa.trino.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opa.trino.cli.ResponseJudge.Contract;
import io.opa.trino.cli.ResponseJudge.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 6 unit tests: CLI exit codes, fail-fast behavior, and — most
 * importantly — that the judge (real OpaResponseParser / DescriptorRenderer /
 * SqlExpressionValidator) rejects EVERY deliberately-broken response from the
 * M5 kit's fixtures/broken/broken.rego with a clause-naming message.
 * OPA-dependent end-to-end runs are in {@link ConformanceCliOpaIntegrationTest}.
 */
class ConformanceCliTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ResponseJudge judge = new ResponseJudge(1, 10); // max-in 10 mirrors the kit's negative tests

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // Exit codes: fail fast (exit 2) without needing opa or policies
    // ------------------------------------------------------------------

    private record RunResult(int exitCode, String out, String err)
    {
        String all() { return out + err; }
    }

    private RunResult execute(String... args)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new ConformanceCli(new PrintStream(out), new PrintStream(err)).execute(args);
        return new RunResult(code, out.toString(), err.toString());
    }

    @Test
    void missingPolicyDirFailsFastWithExit2()
    {
        assertThat(execute("conformance").exitCode()).isEqualTo(2);
        assertThat(execute("conformance").all()).contains("--policy-dir is required");
    }

    @Test
    void nonexistentPolicyDirFailsFastWithExit2()
    {
        RunResult result = execute("conformance", "--policy-dir", tempDir.resolve("nope").toString());
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.all()).contains("policy directory does not exist");
    }

    @Test
    void missingOpaBinaryFailsFastWithExit2()
    {
        RunResult result = execute("conformance", "--policy-dir", tempDir.toString(),
                "--opa", "/nonexistent/opa-binary");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.all()).contains("opa binary not found");
        assertThat(result.all()).contains("the gate cannot run without it");
    }

    @Test
    void invalidModeFailsFastWithExit2()
    {
        RunResult result = execute("conformance", "--policy-dir", tempDir.toString(), "--mode", "bogus",
                "--opa", "/nonexistent/opa-binary");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.all()).contains("--mode must be 'passthrough' or 'safe'");
    }

    @Test
    void missingOpaIsCheckedBeforeFixturesFile()
    {
        RunResult result = execute("conformance", "--policy-dir", tempDir.toString(),
                "--fixtures", tempDir.resolve("missing.json").toString(),
                "--opa", "/nonexistent/opa-binary");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.all()).contains("opa binary not found");
    }

    @Test
    void unknownOptionFailsFastWithExit2()
    {
        assertThat(execute("conformance", "--bogus", "x").exitCode()).isEqualTo(2);
    }

    @Test
    void unknownCommandFailsFastWithExit2()
    {
        assertThat(execute("deploy").exitCode()).isEqualTo(2);
    }

    @Test
    void helpExitsZero()
    {
        assertThat(execute("--help").exitCode()).isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // Fixture-name → contract mapping (kit convention)
    // ------------------------------------------------------------------

    @Test
    void fixtureNameMapsToContract()
    {
        assertThat(ConformanceCli.contractFor("create_table_alice")).isEqualTo(Contract.ALLOW);
        assertThat(ConformanceCli.contractFor("select_columns_alice")).isEqualTo(Contract.ALLOW);
        assertThat(ConformanceCli.contractFor("create_table_nobody")).isEqualTo(Contract.ALLOW);
        assertThat(ConformanceCli.contractFor("row_filters_alice")).isEqualTo(Contract.ROW_FILTERS);
        assertThat(ConformanceCli.contractFor("row_filters_nobody")).isEqualTo(Contract.ROW_FILTERS);
        assertThat(ConformanceCli.contractFor("column_masks_ssn")).isEqualTo(Contract.COLUMN_MASKS);
        assertThat(ConformanceCli.contractFor("filter_schemas")).isEqualTo(Contract.FILTER);
    }

    // ------------------------------------------------------------------
    // Judge: allow (§3.2.A) rejection classes
    // ------------------------------------------------------------------

    private Verdict judgeAllow(ResponseJudge j, String json) throws Exception
    {
        return j.judge(Contract.ALLOW, "passthrough", MAPPER.readTree(json), null, null);
    }

    @Test
    void allowNonBooleanRejected() throws Exception
    {
        Verdict v = judgeAllow(judge, "{\"schema_version\":1,\"result\":\"yes\"}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.clause()).isEqualTo("§3.2.A");
        assertThat(v.detail()).contains("boolean or per-column object");
    }

    @Test
    void allowUnsupportedSchemaVersionRejected() throws Exception
    {
        Verdict v = judgeAllow(judge, "{\"schema_version\":2,\"result\":true}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("Unsupported OPA schema_version 2");
    }

    @Test
    void allowMissingSchemaVersionRejected() throws Exception
    {
        Verdict v = judgeAllow(judge, "{\"result\":true}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("missing an integer 'schema_version'");
    }

    @Test
    void allowPerColumnNonBooleanRejected() throws Exception
    {
        Verdict v = judgeAllow(judge, "{\"schema_version\":1,\"result\":{\"ssn\":\"yes\"}}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("must be booleans");
    }

    @Test
    void allowUndefinedRuleIsDenyNotError() throws Exception
    {
        assertThat(judgeAllow(judge, "{}").conforms()).isTrue();
        assertThat(judgeAllow(judge, "{\"result\":null}").conforms()).isTrue();
    }

    @Test
    void allowBooleanAndPerColumnMapAccepted() throws Exception
    {
        assertThat(judgeAllow(judge, "{\"schema_version\":1,\"result\":true}").conforms()).isTrue();
        assertThat(judgeAllow(judge, "{\"schema_version\":1,\"result\":{\"name\":true,\"ssn\":false}}").conforms()).isTrue();
    }

    // ------------------------------------------------------------------
    // Judge: row_filters (§3.2.B) — passthrough + safe
    // ------------------------------------------------------------------

    private Verdict judgeRowFilters(ResponseJudge j, String mode, String json) throws Exception
    {
        return j.judge(Contract.ROW_FILTERS, mode, MAPPER.readTree(json), "lakehouse.finance.salaries", null);
    }

    @Test
    void rowFiltersUndefinedIsErrorNotDeny() throws Exception
    {
        // List contracts fail closed on an undefined rule (§3.2.D) — unlike allow.
        assertThat(judgeRowFilters(judge, "passthrough", "{}").conforms()).isFalse();
        assertThat(judgeRowFilters(judge, "passthrough", "{\"schema_version\":1}").detail())
                .contains("missing 'result'");
    }

    @Test
    void rowFiltersNonStringEntriesRejectedInPassthrough() throws Exception
    {
        Verdict v = judgeRowFilters(judge, "passthrough", "{\"schema_version\":1,\"result\":[42]}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("entries must be strings");
    }

    @Test
    void rowFiltersNonArrayRejected() throws Exception
    {
        Verdict v = judgeRowFilters(judge, "passthrough", "{\"schema_version\":1,\"result\":\"tenant_id = 'x'\"}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("must be an array");
    }

    @Test
    void rowFiltersNonDescriptorEntryRejectedInSafeMode() throws Exception
    {
        Verdict v = judgeRowFilters(judge, "safe", "{\"schema_version\":1,\"result\":[42]}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("descriptor objects");
    }

    @Test
    void rowFiltersSqlThatDoesNotParseRejectedInPassthrough() throws Exception
    {
        // Contract 4: raw SQL must parse as a single Trino expression over the target.
        Verdict v = judgeRowFilters(judge, "passthrough",
                "{\"schema_version\":1,\"result\":[\"'pii_admin' IN input.identity.groups\"]}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("does not parse");
    }

    @Test
    void rowFiltersPassthroughSqlAccepted() throws Exception
    {
        assertThat(judgeRowFilters(judge, "passthrough",
                "{\"schema_version\":1,\"result\":[\"legal_entity_code IN ('LE_DE_01', 'LE_FR_02')\"]}").conforms()).isTrue();
        assertThat(judgeRowFilters(judge, "passthrough",
                "{\"schema_version\":1,\"result\":[]}").conforms()).isTrue();
    }

    @Test
    void rowFiltersSafeDescriptorsAcceptedAndRendered() throws Exception
    {
        assertThat(judgeRowFilters(judge, "safe",
                "{\"schema_version\":1,\"result\":[]}").conforms()).isTrue();
        assertThat(judgeRowFilters(judge, "safe",
                "{\"schema_version\":1,\"result\":[{\"op\":\"in\",\"column\":\"legal_entity_code\",\"values\":[\"O'Brien Holdings\"]}]}")
                .conforms()).isTrue();
    }

    // ------------------------------------------------------------------
    // Judge: safe-mode descriptor schema (§3.4) — every broken descriptor
    // ------------------------------------------------------------------

    @Test
    void descriptorUnsupportedOpRejected() throws Exception
    {
        Verdict v = judgeRowFilters(judge, "safe",
                "{\"schema_version\":1,\"result\":[{\"op\":\"like\",\"column\":\"name\",\"values\":[\"%admin%\"]}]}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("unsupported 'op'");
    }

    @Test
    void descriptorUnsafeColumnRejected() throws Exception
    {
        Verdict v = judgeRowFilters(judge, "safe",
                "{\"schema_version\":1,\"result\":[{\"op\":\"eq\",\"column\":\"1=1 OR true --\",\"values\":[\"x\"]}]}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("not a safe identifier");
    }

    @Test
    void descriptorMissingValuesRejected() throws Exception
    {
        Verdict v = judgeRowFilters(judge, "safe",
                "{\"schema_version\":1,\"result\":[{\"op\":\"in\",\"column\":\"org_unit_id\"}]}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("requires a 'values' array");
    }

    @Test
    void descriptorNonScalarValueRejected() throws Exception
    {
        Verdict v = judgeRowFilters(judge, "safe",
                "{\"schema_version\":1,\"result\":[{\"op\":\"in\",\"column\":\"org_unit_id\",\"values\":[{\"nested\":\"object\"}]}]}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("scalars");
    }

    @Test
    void descriptorEqWrongArityRejected() throws Exception
    {
        Verdict v = judgeRowFilters(judge, "safe",
                "{\"schema_version\":1,\"result\":[{\"op\":\"eq\",\"column\":\"country_iso\",\"values\":[\"DE\",\"FR\"]}]}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("exactly one value");
    }

    @Test
    void descriptorOversizedInRejected() throws Exception
    {
        StringBuilder values = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            values.append(i > 1 ? "," : "").append("\"v").append(i).append("\"");
        }
        Verdict v = judgeRowFilters(judge, "safe",
                "{\"schema_version\":1,\"result\":[{\"op\":\"in\",\"column\":\"org_unit_id\",\"values\":[" + values + "]}]}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("max-in-clause-size");
    }

    @Test
    void descriptorInAtLimitAccepted() throws Exception
    {
        StringBuilder values = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            values.append(i > 1 ? "," : "").append("\"v").append(i).append("\"");
        }
        Verdict v = judgeRowFilters(judge, "safe",
                "{\"schema_version\":1,\"result\":[{\"op\":\"in\",\"column\":\"org_unit_id\",\"values\":[" + values + "]}]}");
        assertThat(v.conforms()).isTrue();
    }

    // ------------------------------------------------------------------
    // Judge: column_masks (§3.2.C) + filter (§3.2.D)
    // ------------------------------------------------------------------

    private Verdict judgeMask(ResponseJudge j, String mode, String json) throws Exception
    {
        return j.judge(Contract.COLUMN_MASKS, mode, MAPPER.readTree(json), "lakehouse.finance.customers", "ssn");
    }

    @Test
    void maskUndefinedIsErrorNotDeny() throws Exception
    {
        assertThat(judgeMask(judge, "passthrough", "{}").conforms()).isFalse();
        assertThat(judgeMask(judge, "passthrough", "{\"schema_version\":1}").detail()).contains("missing 'result'");
    }

    @Test
    void maskNonStringRejectedInPassthrough() throws Exception
    {
        Verdict v = judgeMask(judge, "passthrough", "{\"schema_version\":1,\"result\":7}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("must be a string or null");
    }

    @Test
    void maskValidPassthroughSqlAccepted() throws Exception
    {
        assertThat(judgeMask(judge, "passthrough",
                "{\"schema_version\":1,\"result\":\"CASE WHEN ssn IS NULL THEN NULL ELSE '***-**-' || substr(ssn, 8) END\"}")
                .conforms()).isTrue();
        assertThat(judgeMask(judge, "passthrough", "{\"schema_version\":1,\"result\":null}").conforms()).isTrue();
    }

    @Test
    void maskValidSafeDescriptorAccepted() throws Exception
    {
        assertThat(judgeMask(judge, "safe",
                "{\"schema_version\":1,\"result\":{\"op\":\"is_null\",\"column\":\"ssn\"}}").conforms()).isTrue();
        assertThat(judgeMask(judge, "safe", "{\"schema_version\":1,\"result\":null}").conforms()).isTrue();
    }

    @Test
    void maskReferencingForeignColumnRejected() throws Exception
    {
        // Contract 4: the mask may only reference the masked column.
        Verdict v = judgeMask(judge, "passthrough",
                "{\"schema_version\":1,\"result\":\"CASE WHEN other_col = 'x' THEN ssn ELSE '***' END\"}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("outside the target resource");
    }

    private Verdict judgeFilter(String json) throws Exception
    {
        return judge.judge(Contract.FILTER, "passthrough", MAPPER.readTree(json), null, null);
    }

    @Test
    void filterUndefinedIsErrorNotDeny() throws Exception
    {
        assertThat(judgeFilter("{}").conforms()).isFalse();
        assertThat(judgeFilter("{\"schema_version\":1}").detail()).contains("missing 'result'");
    }

    @Test
    void filterNonStringEntriesRejected() throws Exception
    {
        Verdict v = judgeFilter("{\"schema_version\":1,\"result\":[{\"name\":\"finance\"}]}");
        assertThat(v.conforms()).isFalse();
        assertThat(v.detail()).contains("entries must be strings");
    }

    @Test
    void filterValidAccepted() throws Exception
    {
        assertThat(judgeFilter("{\"schema_version\":1,\"result\":[\"finance\"]}").conforms()).isTrue();
        assertThat(judgeFilter("{\"schema_version\":1,\"result\":[]}").conforms()).isTrue();
    }

    // ------------------------------------------------------------------
    // Version-skew parity: a response valid under one validator
    // configuration, rejected under another
    // ------------------------------------------------------------------

    @Test
    void schemaVersionSkewParity() throws Exception
    {
        // A policy written against TOMORROW's contract (schema_version 2):
        // rejected by today's plugin (supported=1), accepted by a parser configured for 2.
        ResponseJudge today = new ResponseJudge(1, 1000);
        ResponseJudge tomorrow = new ResponseJudge(2, 1000);
        JsonNode envelope = MAPPER.readTree("{\"schema_version\":2,\"result\":true}");
        assertThat(today.judge(Contract.ALLOW, "passthrough", envelope, null, null).conforms()).isFalse();
        assertThat(tomorrow.judge(Contract.ALLOW, "passthrough", envelope, null, null).conforms()).isTrue();
    }

    @Test
    void maxInClauseSizeSkewParity() throws Exception
    {
        // An IN descriptor with 11 values: valid under the plugin default (1000),
        // rejected when the deployment tightens opa.sql.max-in-clause-size to 10.
        ResponseJudge lax = new ResponseJudge(1, 1000);
        ResponseJudge strict = new ResponseJudge(1, 10);
        JsonNode envelope = MAPPER.readTree(
                "{\"schema_version\":1,\"result\":[{\"op\":\"in\",\"column\":\"org_unit_id\",\"values\":"
                + "[\"v1\",\"v2\",\"v3\",\"v4\",\"v5\",\"v6\",\"v7\",\"v8\",\"v9\",\"v10\",\"v11\"]}]}");
        assertThat(lax.judge(Contract.ROW_FILTERS, "safe", envelope, "lakehouse.finance.salaries", null).conforms()).isTrue();
        Verdict strictVerdict = strict.judge(Contract.ROW_FILTERS, "safe", envelope, "lakehouse.finance.salaries", null);
        assertThat(strictVerdict.conforms()).isFalse();
        assertThat(strictVerdict.detail()).contains("max-in-clause-size");
    }

    // ------------------------------------------------------------------
    // Fixtures file shape
    // ------------------------------------------------------------------

    @Test
    void fixturesFileWithoutInputsSectionFailsFast(@TempDir Path dir) throws Exception
    {
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{\"something_else\": {}}");
        RunResult result = execute("conformance", "--policy-dir", dir.toString(),
                "--fixtures", bad.toString(), "--opa", "/nonexistent/opa-binary");
        assertThat(result.exitCode()).isEqualTo(2);
    }
}
