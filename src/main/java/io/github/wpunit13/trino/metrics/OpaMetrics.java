package io.github.wpunit13.trino.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.concurrent.TimeUnit;

/**
 * Observability for the plugin (ARCHITECTURE.md §8.4), built on Micrometer:
 * - decision latency histogram, tagged cache_hit vs cache_miss (and by action)
 * - allow/deny decision counters
 * - fail-closed counter (leading indicator of PDP health)
 * - OPA error counters by kind (transport / http_status / timeout / malformed / other)
 * - circuit-breaker state gauge
 *
 * Every decision is correlated by decision_id via the decision logger, not metrics
 * (metrics intentionally carry no per-decision identifiers).
 */
public record OpaMetrics(MeterRegistry registry)
{
    public static final String METRIC_LATENCY = "opa.decision.latency";
    public static final String METRIC_DECISIONS = "opa.decisions";
    public static final String METRIC_FAIL_CLOSED = "opa.fail.closed";
    public static final String METRIC_ERRORS = "opa.errors";
    public static final String METRIC_CACHE_SIZE = "opa.cache.size";
    public static final String METRIC_CACHE_EVICTIONS = "opa.cache.evictions";

    private static final String TAG_ACTION = "action";
    private static final String TAG_CACHE = "cache";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_KIND = "kind";

    public enum ErrorKind { TRANSPORT, HTTP_STATUS, TIMEOUT, MALFORMED, OTHER }

    public static OpaMetrics createDefault()
    {
        return new OpaMetrics(new SimpleMeterRegistry());
    }

    public void recordDecision(String action, boolean allowed, boolean cacheHit, long latencyNanos)
    {
        Timer.builder(METRIC_LATENCY)
                .description("OPA decision latency")
                .tag(TAG_ACTION, action)
                .tag(TAG_CACHE, cacheHit ? "hit" : "miss")
                .register(registry)
                .record(latencyNanos, TimeUnit.NANOSECONDS);
        Counter.builder(METRIC_DECISIONS)
                .description("OPA decisions")
                .tag(TAG_ACTION, action)
                .tag(TAG_OUTCOME, allowed ? "allow" : "deny")
                .tag(TAG_CACHE, cacheHit ? "hit" : "miss")
                .register(registry)
                .increment();
    }

    public void recordFailClosed(String action)
    {
        Counter.builder(METRIC_FAIL_CLOSED)
                .description("Fail-closed denials (leading indicator of PDP health)")
                .tag(TAG_ACTION, action)
                .register(registry)
                .increment();
    }

    public void recordError(ErrorKind kind)
    {
        Counter.builder(METRIC_ERRORS)
                .description("OPA client errors")
                .tag(TAG_KIND, kind.name().toLowerCase(java.util.Locale.ROOT))
                .register(registry)
                .increment();
    }

    /**
     * D5 (ROADMAP.md M7): registers the decision-cache size gauge and the
     * cumulative-eviction counter for one cache (decisions / volatile / negative),
     * closing the last §8.4 gap. Evictions are a cumulative {@code FunctionCounter}
     * — the monitoring backend derives the rate from it (a plain gauge would be
     * wrong for a monotonically increasing count).
     */
    public void registerCacheGauges(String cacheName, java.util.function.LongSupplier size, java.util.function.LongSupplier evictions)
    {
        io.micrometer.core.instrument.Gauge.builder(METRIC_CACHE_SIZE, size, java.util.function.LongSupplier::getAsLong)
                .description("Decision cache current size")
                .tag(TAG_CACHE, cacheName)
                .register(registry);
        io.micrometer.core.instrument.FunctionCounter.builder(METRIC_CACHE_EVICTIONS, evictions, java.util.function.LongSupplier::getAsLong)
                .description("Decision cache cumulative evictions (backend derives rate)")
                .tag(TAG_CACHE, cacheName)
                .register(registry);
    }
}