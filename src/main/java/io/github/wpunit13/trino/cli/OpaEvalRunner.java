package io.github.wpunit13.trino.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Spawns a throwaway {@code opa eval} subprocess per (fixture, document) query
 * against the policy directory (Milestone 6). The jar never parses Rego — OPA
 * is the only Rego interpreter; this class only converts the eval output into
 * the envelope shape the plugin's {@code OpaHttpClient} would hand to
 * {@code OpaResponseParser}:
 *
 * <ul>
 *   <li>defined document → {@code {"result": <policy output>}} (OPA data-API shape)</li>
 *   <li>undefined document → {@code {}} (what the OPA server returns; the parser's
 *       undefined-rule semantics — deny for allow, error for lists/masks — apply)</li>
 * </ul>
 */
public final class OpaEvalRunner
{
    /** Thrown when opa eval itself fails (compile error, evaluation error). The plugin would receive an OPA error envelope and fail closed. */
    public static final class OpaEvalException extends Exception
    {
        public OpaEvalException(String message)
        {
            super(message);
        }
    }

    private static final long EVAL_TIMEOUT_SECONDS = 120;

    private final String opaBinary;
    private final List<Path> policyDirs;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpaEvalRunner(String opaBinary, List<Path> policyDirs)
    {
        this.opaBinary = opaBinary;
        this.policyDirs = List.copyOf(policyDirs);
    }

    /** @return the envelope for the given document; never null, may be the empty (undefined) object. */
    public JsonNode evaluateDocument(JsonNode input, String document) throws OpaEvalException, IOException, InterruptedException
    {
        // Create a private (0700) temp directory so the throwaway input file is not
        // written into a world-writable location with a predictable name (S5443).
        Path tempDir = Files.createTempDirectory("opa-conformance-");
        try {
            Path inputFile = tempDir.resolve("input.json");
            mapper.writer().writeValue(inputFile.toFile(), input);
            List<String> command = new ArrayList<>(List.of(opaBinary, "eval", "--format=json"));
            for (Path policyDir : policyDirs) {
                command.add("--data");
                command.add(policyDir.toAbsolutePath().toString());
            }
            command.addAll(List.of(
                    "--input", inputFile.toAbsolutePath().toString(),
                    document));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();
            if (!process.waitFor(EVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new OpaEvalException("opa eval timed out after " + EVAL_TIMEOUT_SECONDS + "s for document " + document);
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new OpaEvalException("opa eval failed (exit " + process.exitValue() + ") for document " + document
                        + (stderr.isEmpty() ? "" : ": " + lastLine(stderr)));
            }
            JsonNode evalOutput = mapper.readTree(stdout);
            if (evalOutput == null || !evalOutput.isObject()) {
                throw new OpaEvalException("opa eval produced unparseable output for document " + document);
            }
            return envelope(evalOutput, document);
        }
        finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path root) throws IOException
    {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path entry : stream.toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    /**
     * Converts the raw {@code opa eval --format=json} output into the OPA
     * data-API envelope. Defined: {@code result[0].expressions[0].value}.
     * Undefined: OPA emits {@code {"result": []}}; the server responds with
     * {@code {}}, which is what we mirror.
     */
    private JsonNode envelope(JsonNode evalOutput, String document) throws OpaEvalException
    {
        JsonNode results = evalOutput.get("result");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return mapper.createObjectNode(); // undefined document
        }
        JsonNode value = results.get(0).path("expressions").path(0).path("value");
        if (value.isMissingNode()) {
            throw new OpaEvalException("opa eval output has no expression value for document " + document);
        }
        ObjectNode envelope = mapper.createObjectNode();
        envelope.set("result", value);
        return envelope;
    }

    private static String lastLine(String stderr)
    {
        String[] lines = stderr.split("\\R");
        return lines[lines.length - 1];
    }
}
