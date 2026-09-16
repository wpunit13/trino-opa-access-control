package io.github.wpunit13.trino.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wpunit13.trino.cli.ResponseJudge.Contract;
import io.github.wpunit13.trino.cli.ResponseJudge.Verdict;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * CLI conformance runner (Milestone 6): the CI gate for Rego policy repos.
 *
 * <pre>
 * java -jar trino-opa-access-control-&lt;v&gt;-conformance-cli.jar conformance --policy-dir &lt;dir&gt;
 *      [--policy-dir &lt;dir&gt; ...] [--mode passthrough|safe] [--fixtures &lt;path&gt;] [--opa &lt;path&gt;]
 *      [--max-in-clause-size &lt;n&gt;] [--schema-version &lt;n&gt;]
 * </pre>
 *
 * Spawns a throwaway {@code opa eval} per fixture against the policy directory
 * (OPA is the only Rego interpreter — the jar never parses Rego), then judges
 * every response through the plugin's authoritative {@code OpaResponseParser} /
 * {@code DescriptorRenderer} / {@code SqlExpressionValidator}.
 *
 * Exit codes:
 * <ul>
 *   <li>0 — every response conforms; the bundle is publishable</li>
 *   <li>1 — the plugin would reject a response (contract violation; message names
 *       the clause and the fixture); also for OPA evaluation errors (fail closed)</li>
 *   <li>2 — fail fast: bad usage, missing opa binary, missing policy dir or fixtures
 *       (the gate must never be silently skipped)</li>
 * </ul>
 */
public final class ConformanceCli
{
    // Defaults pinned to the plugin's OpaConfig defaults at implementation time
    // (asserted by ConformanceCliDefaultsTest so they cannot drift silently).
    // D6: mirrors OpaConfig's safe default.
    public static final String DEFAULT_SQL_MODE = "safe";
    public static final int DEFAULT_MAX_IN_CLAUSE_SIZE = 1_000;
    public static final int DEFAULT_SCHEMA_VERSION = 1;

    static final int EXIT_CONFORMS = 0;
    static final int EXIT_VIOLATION = 1;
    static final int EXIT_FAIL_FAST = 2;

    private static final String USAGE =
            "usage: conformance --policy-dir <dir> [--mode passthrough|safe] [--fixtures <path>]\n"
            + "                   [--opa <path>] [--max-in-clause-size <n>] [--schema-version <n>]\n"
            + "\n"
            + "  --policy-dir <dir>           directory with the Rego policy under test (required; may be repeated — all dirs are loaded as Rego data documents)\n"
            + "  --mode passthrough|safe      SQL mode to validate against (default: " + DEFAULT_SQL_MODE + ")\n"
            + "  --fixtures <path>            Contract-1 input fixtures (default: policy-conformance-kit/fixtures/contract1_inputs.json)\n"
            + "  --opa <path>                 path to the opa binary (default: opa on PATH, needs >= 1.0)\n"
            + "  --max-in-clause-size <n>     safe-mode IN bound, mirrors opa.sql.max-in-clause-size (default: " + DEFAULT_MAX_IN_CLAUSE_SIZE + ")\n"
            + "  --schema-version <n>         supported response schema_version, mirrors the plugin (default: " + DEFAULT_SCHEMA_VERSION + ")\n"
            + "\nExit codes: 0 = all responses conform; 1 = plugin would reject a response (clause named); 2 = fail fast (bad invocation/op missing).";

    private final PrintStream out;
    private final PrintStream err;

    public ConformanceCli(PrintStream out, PrintStream err)
    {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args)
    {
        System.exit(run(args));
    }

    static int run(String[] args)
    {
        return new ConformanceCli(System.out, System.err).execute(args);
    }

    int execute(String[] args)
    {
        try {
            return dispatch(args);
        }
        catch (FailFast e) {
            err.println("ERROR: " + e.getMessage());
            err.println(USAGE);
            return EXIT_FAIL_FAST;
        }
        catch (Exception e) {
            err.println("ERROR: " + e.getMessage());
            return EXIT_FAIL_FAST;
        }
    }

    private int dispatch(String[] args) throws IOException, InterruptedException
    {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            out.println(USAGE);
            return args.length == 0 ? EXIT_FAIL_FAST : EXIT_CONFORMS;
        }
        if (!args[0].equals("conformance")) {
            throw new FailFast("unknown command '" + args[0] + "' (only 'conformance' is supported)");
        }

        Options options = parseArgs(args);
        validateInvocation(options);

        return runConformance(options.policyDirs, options.mode, options.fixtures, options.opaBinary,
                options.maxInClauseSize, options.schemaVersion);
    }

    private record Options(List<Path> policyDirs, String mode, Path fixtures, String opaBinary,
                           int maxInClauseSize, int schemaVersion) {}

    private Options parseArgs(String[] args)
    {
        List<Path> policyDirs = new ArrayList<>();
        String mode = DEFAULT_SQL_MODE;
        Path fixtures = Path.of("policy-conformance-kit", "fixtures", "contract1_inputs.json");
        String opaBinary = "opa";
        int maxInClauseSize = DEFAULT_MAX_IN_CLAUSE_SIZE;
        int schemaVersion = DEFAULT_SCHEMA_VERSION;

        for (int i = 1; i < args.length; i += 2) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                throw new FailFast("missing value for " + flag);
            }
            String value = args[i + 1];
            switch (flag) {
                case "--policy-dir" -> policyDirs.add(Path.of(value));
                case "--mode" -> mode = value;
                case "--fixtures" -> fixtures = Path.of(value);
                case "--opa" -> opaBinary = value;
                case "--max-in-clause-size" -> maxInClauseSize = parseInt(flag, value);
                case "--schema-version" -> schemaVersion = parseInt(flag, value);
                default -> throw new FailFast("unknown option '" + flag + "'");
            }
        }
        return new Options(policyDirs, mode, fixtures, opaBinary, maxInClauseSize, schemaVersion);
    }

    /** Fail fast (exit 2): the gate must never be silently skipped. */
    private void validateInvocation(Options options) throws IOException, InterruptedException
    {
        if (options.policyDirs.isEmpty()) {
            throw new FailFast("--policy-dir is required");
        }
        for (Path policyDir : options.policyDirs) {
            if (!Files.isDirectory(policyDir)) {
                throw new FailFast("policy directory does not exist: " + policyDir);
            }
        }
        if (!"passthrough".equalsIgnoreCase(options.mode) && !"safe".equalsIgnoreCase(options.mode)) {
            throw new FailFast("--mode must be 'passthrough' or 'safe': " + options.mode);
        }
        if (options.maxInClauseSize <= 0) {
            throw new FailFast("--max-in-clause-size must be > 0: " + options.maxInClauseSize);
        }
        requireOpaBinary(options.opaBinary);
        if (!Files.isRegularFile(options.fixtures)) {
            throw new FailFast("fixtures file does not exist: " + options.fixtures
                    + " (pass --fixtures <path>, default is the M5 kit fixtures)");
        }
    }

    private int runConformance(List<Path> policyDirs, String mode, Path fixtures, String opaBinary,
                               int maxInClauseSize, int schemaVersion) throws IOException
    {
        Map<String, JsonNode> inputs = loadFixtures(fixtures);
        ResponseJudge judge = new ResponseJudge(schemaVersion, maxInClauseSize);
        OpaEvalRunner opa = new OpaEvalRunner(opaBinary, policyDirs);

        out.println("== conformance: policy dir(s) " + policyDirs + " (mode=" + mode.toLowerCase()
                + ", fixtures=" + fixtures + ", inputs=" + inputs.size() + ", schema_version=" + schemaVersion
                + ", max_in_clause_size=" + maxInClauseSize + ") ==");

        int checked = 0;
        for (Map.Entry<String, JsonNode> fixture : inputs.entrySet()) {
            String name = fixture.getKey();
            JsonNode input = fixture.getValue();
            Contract contract = contractFor(name);

            JsonNode envelope;
            try {
                envelope = opa.evaluateDocument(input, "data.trino." + contract.name);
            }
            catch (OpaEvalRunner.OpaEvalException e) {
                // The plugin receives an OPA error envelope and fails closed (§7) —
                // that is a rejection, not a skip.
                out.printf("FAIL   %-40s %s%n", name, contract.name);
                err.printf("CONFORMANCE FAILURE: fixture '%s', contract '%s' (clause %s): the plugin would fail closed%n"
                        + "  OPA evaluation error: %s%n", name, contract.name, contract.clause, e.getMessage());
                return EXIT_VIOLATION;
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while running opa eval", e);
            }

            Verdict verdict = judge.judge(contract, mode, envelope, targetOf(input), columnOf(input));
            checked++;
            if (verdict.conforms()) {
                out.printf("PASS   %-40s %s%n", name, contract.name);
            }
            else {
                out.printf("FAIL   %-40s %s%n", name, contract.name);
                err.printf("CONFORMANCE FAILURE: fixture '%s', contract '%s' (clause %s): the plugin would reject this response%n"
                        + "  %s%n", name, contract.name, verdict.clause(), verdict.detail());
                return EXIT_VIOLATION;
            }
        }

        out.println("== CONFORMANCE OK: " + checked + " response(s) conform; bundle publishable ==");
        return EXIT_CONFORMS;
    }

    private Map<String, JsonNode> loadFixtures(Path fixtures) throws IOException
    {
        JsonNode root = new ObjectMapper().readTree(fixtures.toFile());
        JsonNode inputs = root == null ? null : root.get("inputs");
        if (inputs == null || !inputs.isObject()) {
            throw new FailFast("fixtures file must be {\"inputs\": {<name>: <Contract-1 input>}}: " + fixtures);
        }
        Map<String, JsonNode> map = new LinkedHashMap<>();
        inputs.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue()));
        if (map.isEmpty()) {
            throw new FailFast("fixtures file contains no inputs: " + fixtures);
        }
        return map;
    }

    /**
     * Fixture-name → contract mapping, the same convention the kit's
     * author_conformance_test.rego applies (row_filters_*, column_masks_*,
     * filter_*; everything else exercises allow).
     */
    static Contract contractFor(String fixtureName)
    {
        if (fixtureName.startsWith("row_filters")) {
            return Contract.ROW_FILTERS;
        }
        if (fixtureName.startsWith("column_masks")) {
            return Contract.COLUMN_MASKS;
        }
        if (fixtureName.startsWith("filter")) {
            return Contract.FILTER;
        }
        return Contract.ALLOW;
    }

    /** target "catalog.schema.table" from the fixture input (plugin: targetString(table)). */
    private static String targetOf(JsonNode input)
    {
        JsonNode resource = input.get("resource");
        if (resource == null) {
            return null;
        }
        return streamOfText(resource.get("catalog")) + "." + streamOfText(resource.get("schema")) + "." + streamOfText(resource.get("table"));
    }

    private static String streamOfText(JsonNode node)
    {
        return node == null || node.isNull() ? "null" : node.asText();
    }

    /** The masked column: the single entry of resource.columns (plugin: Set.of(columnName)). */
    private static String columnOf(JsonNode input)
    {
        JsonNode columns = input.path("resource").path("columns");
        return columns.isArray() && columns.size() > 0 ? columns.get(0).asText() : null;
    }

    private void requireOpaBinary(String opaBinary) throws IOException, InterruptedException
    {
        Process process;
        try {
            process = new ProcessBuilder(opaBinary, "version").start();
        }
        catch (IOException e) {
            throw new FailFast("opa binary not found: '" + opaBinary + "' (need opa >= 1.0 on PATH or via --opa). "
                    + "Install from https://www.openpolicyagent.org — the gate cannot run without it.");
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new FailFast("opa binary did not respond: '" + opaBinary + "'");
        }
        if (process.exitValue() != 0) {
            throw new FailFast("opa binary is not runnable: '" + opaBinary + "'");
        }
    }

    private static int parseInt(String flag, String value)
    {
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e) {
            throw new FailFast(flag + " must be an integer: " + value);
        }
    }

    private static final class FailFast extends RuntimeException
    {
        FailFast(String message)
        {
            super(message);
        }
    }
}
