package io.github.wpunit13.trino.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 6 integration tests: the M5 kit through the REAL CLI, with OPA
 * spawned as a subprocess (the jar never parses Rego).
 *
 * Requires an {@code opa} >= 1.0 binary (PATH or {@code OPA_BIN}); tests are
 * skipped when it is absent so that plain {@code mvn test} stays green
 * everywhere. In CI install opa first so these run as the gate regression.
 */
class ConformanceCliOpaIntegrationTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KIT = "policy-conformance-kit";

    private static String opaBinary;
    private static boolean opaAvailable;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void findOpa()
    {
        opaBinary = System.getenv().getOrDefault("OPA_BIN", "opa");
        try {
            Process process = new ProcessBuilder(opaBinary, "version").start();
            opaAvailable = process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        }
        catch (Exception e) {
            opaAvailable = false;
        }
    }

    private void assumeOpa()
    {
        Assumptions.assumeTrue(opaAvailable, "opa >= 1.0 not available on PATH/OPA_BIN");
    }

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

    // ------------------------------------------------------------------
    // Positive: M5 example policies through the CLI
    // ------------------------------------------------------------------

    @Test
    void passthroughExampleConforms()
    {
        assumeOpa();
        RunResult result = execute("conformance",
                "--policy-dir", KIT + "/examples/passthrough",
                "--mode", "passthrough");
        assertThat(result.exitCode()).as(result.all()).isEqualTo(0);
        assertThat(result.out()).contains("CONFORMANCE OK");
    }

    @Test
    void safeExampleConforms()
    {
        assumeOpa();
        RunResult result = execute("conformance",
                "--policy-dir", KIT + "/examples/safe",
                "--mode", "safe");
        assertThat(result.exitCode()).as(result.all()).isEqualTo(0);
        assertThat(result.out()).contains("CONFORMANCE OK");
    }

    // ------------------------------------------------------------------
    // Negative: every M5 broken-fixture response must be rejected with a
    // clause-naming message (mock policy serves each broken constant keyed
    // by decision_id; OPA does the Rego, the CLI judges the JSON)
    // ------------------------------------------------------------------

    private static final Map<String, String> BROKEN_INPUT_TEMPLATE = Map.ofEntries(
            Map.entry("allow_non_boolean", "create_table_alice"),
            Map.entry("allow_bad_version", "create_table_alice"),
            Map.entry("allow_missing_version", "create_table_alice"),
            Map.entry("allow_per_column_non_boolean", "select_columns_alice"),
            Map.entry("row_filters_non_string_entries", "row_filters_alice"),
            Map.entry("row_filters_missing_result", "row_filters_alice"),
            Map.entry("row_filters_non_array", "row_filters_alice"),
            Map.entry("mask_non_string", "column_masks_ssn"),
            Map.entry("mask_missing_result", "column_masks_ssn"),
            Map.entry("filter_missing_result", "filter_schemas"),
            Map.entry("filter_non_string_entries", "filter_schemas"),
            Map.entry("descriptor_unsupported_op", "row_filters_alice"),
            Map.entry("descriptor_unsafe_column", "row_filters_alice"),
            Map.entry("descriptor_missing_values", "row_filters_alice"),
            Map.entry("descriptor_non_scalar_value", "row_filters_alice"),
            Map.entry("descriptor_eq_wrong_arity", "row_filters_alice"),
            Map.entry("descriptor_oversized_in", "row_filters_alice"),
            Map.entry("descriptor_in_at_limit", "row_filters_alice"));

    private static final List<String> SAFE_MODE_ACCEPTED = List.of("descriptor_in_at_limit");

    /** Fixture name for a broken constant: the contract prefix decides the data path. */
    private static String fixtureName(String brokenConstant)
    {
        if (brokenConstant.startsWith("allow_")) {
            return "create_table_case_" + brokenConstant;
        }
        if (brokenConstant.startsWith("row_filters_")) {
            return "row_filters_case_" + brokenConstant;
        }
        if (brokenConstant.startsWith("mask_")) {
            return "column_masks_case_" + brokenConstant;
        }
        if (brokenConstant.startsWith("filter_")) {
            return "filter_case_" + brokenConstant;
        }
        return "row_filters_case_" + brokenConstant; // descriptor_*
    }

    /** Writes a fixtures file with a single broken case; the mock policy selects by decision_id. */
    private Path singleCaseFixtures(String brokenConstant) throws IOException
    {
        JsonNode allFixtures = MAPPER.readTree(Files.newInputStream(Path.of(KIT, "fixtures", "contract1_inputs.json")));
        JsonNode template = allFixtures.get("inputs").get(BROKEN_INPUT_TEMPLATE.get(brokenConstant));
        ObjectNode input = template.deepCopy();
        input.put("decision_id", "case-" + brokenConstant);

        ObjectNode root = MAPPER.createObjectNode();
        root.set("inputs", MAPPER.createObjectNode().set(fixtureName(brokenConstant), input));
        Path file = tempDir.resolve("fixtures-" + brokenConstant + ".json");
        MAPPER.writer().writeValue(file.toFile(), root);
        return file;
    }

    private RunResult runBrokenCase(String brokenConstant, String mode, String maxIn)
    {
        try {
            Path fixtures = singleCaseFixtures(brokenConstant);
            return execute("conformance",
                    "--policy-dir", KIT + "/fixtures/broken",
                    "--policy-dir", KIT + "/cli-negative",
                    "--fixtures", fixtures.toString(),
                    "--mode", mode,
                    "--max-in-clause-size", maxIn);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void everyBrokenCaseRejectedInSafeModeWithClauseMessage()
    {
        assumeOpa();
        for (String brokenConstant : BROKEN_INPUT_TEMPLATE.keySet()) {
            boolean shouldAccept = SAFE_MODE_ACCEPTED.contains(brokenConstant);
            RunResult result = runBrokenCase(brokenConstant, "safe", "10");
            if (shouldAccept) {
                assertThat(result.exitCode())
                        .as("%s must be ACCEPTED at the IN boundary:%n%s", brokenConstant, result.all())
                        .isEqualTo(0);
            }
            else {
                assertThat(result.exitCode())
                        .as("%s must be REJECTED:%n%s", brokenConstant, result.all())
                        .isEqualTo(1);
                assertThat(result.all())
                        .as("failure must name the fixture and the contract clause")
                        .contains(fixtureName(brokenConstant))
                        .contains("clause §3.2.")
                        .contains("the plugin would reject this response");
            }
        }
    }

    @Test
    void passthroughSpecificBrokenCasesRejectedInPassthroughMode()
    {
        assumeOpa();
        for (String brokenConstant : List.of("mask_non_string", "row_filters_non_string_entries", "row_filters_non_array")) {
            RunResult result = runBrokenCase(brokenConstant, "passthrough", "1000");
            assertThat(result.exitCode()).as("%s: %s", brokenConstant, result.all()).isEqualTo(1);
        }
    }

    @Test
    void firstRejectionStopsTheRunAndNamesTheFixture() throws IOException
    {
        assumeOpa();
        RunResult result = execute("conformance",
                "--policy-dir", KIT + "/fixtures/broken",
                "--policy-dir", KIT + "/cli-negative",
                "--fixtures", allBrokenCasesFixtures("allow_bad_version").toString(),
                "--mode", "safe",
                "--max-in-clause-size", "10");
        assertThat(result.exitCode()).as(result.all()).isEqualTo(1);
        assertThat(result.err()).contains("create_table_case_allow_bad_version").contains("§3.2.A");
    }

    /** All broken cases in one fixtures file (deterministic order), for first-failure semantics. */
    private Path allBrokenCasesFixtures(String firstConstant) throws IOException
    {
        JsonNode allFixtures = MAPPER.readTree(Files.newInputStream(Path.of(KIT, "fixtures", "contract1_inputs.json")));
        ObjectNode inputs = MAPPER.createObjectNode();
        List<String> ordered = List.copyOf(BROKEN_INPUT_TEMPLATE.keySet());
        List<String> reordered = new java.util.ArrayList<>(ordered);
        reordered.remove(firstConstant);
        reordered.add(0, firstConstant);
        for (String brokenConstant : reordered) {
            ObjectNode input = allFixtures.get("inputs").get(BROKEN_INPUT_TEMPLATE.get(brokenConstant)).deepCopy();
            input.put("decision_id", "case-" + brokenConstant);
            inputs.set(fixtureName(brokenConstant), input);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.set("inputs", inputs);
        Path file = tempDir.resolve("fixtures-all.json");
        MAPPER.writer().writeValue(file.toFile(), root);
        return file;
    }
}
