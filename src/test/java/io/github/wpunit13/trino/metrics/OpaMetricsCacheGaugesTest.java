package io.github.wpunit13.trino.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.wpunit13.trino.cache.DecisionCache;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D5 (ROADMAP.md M7): the decision-cache size gauge and cumulative-eviction
 * counter registered per cache (decisions / volatile / negative) close the last
 * §8.4 observability gap. Verifies the gauges exist with the right tags and
 * reflect real cache state (size grows on puts; evictions accumulate past the
 * max size).
 */
class OpaMetricsCacheGaugesTest
{
    @Test
    void registersSizeAndEvictionGaugesForEachCache()
    {
        MeterRegistry registry = new SimpleMeterRegistry();
        OpaMetrics metrics = new OpaMetrics(registry);
        DecisionCache cache = new DecisionCache(true, 100, 60, 2, List.of());

        metrics.registerCacheGauges("decisions", cache::decisionsSize, cache::decisionsEvictions);
        metrics.registerCacheGauges("volatile", cache::volatileDecisionsSize, cache::volatileDecisionsEvictions);
        metrics.registerCacheGauges("negative", cache::negativeSize, cache::negativeEvictions);

        // All three caches expose both a size gauge and an eviction counter.
        for (String name : List.of("decisions", "volatile", "negative")) {
            assertThat(registry.get(OpaMetrics.METRIC_CACHE_SIZE).tag("cache", name).gauge()).isNotNull();
            assertThat(registry.get(OpaMetrics.METRIC_CACHE_EVICTIONS).tag("cache", name).functionCounter()).isNotNull();
        }
    }

    @Test
    void sizeGaugeReflectsCacheEntries()
    {
        MeterRegistry registry = new SimpleMeterRegistry();
        OpaMetrics metrics = new OpaMetrics(registry);
        DecisionCache cache = new DecisionCache(true, 100, 60, 2, List.of());
        metrics.registerCacheGauges("decisions", cache::decisionsSize, cache::decisionsEvictions);

        cache.put("k1", true, false);
        cache.put("k2", true, false);
        cache.put("k3", true, false);

        assertThat(registry.get(OpaMetrics.METRIC_CACHE_SIZE).tag("cache", "decisions").gauge().value())
                .isEqualTo(3.0);
    }

    @Test
    void evictionsAccumulatePastMaxSize()
    {
        MeterRegistry registry = new SimpleMeterRegistry();
        OpaMetrics metrics = new OpaMetrics(registry);
        // Tiny max size so we can force eviction deterministically.
        DecisionCache cache = new DecisionCache(true, 2, 60, 2, List.of());
        metrics.registerCacheGauges("decisions", cache::decisionsSize, cache::decisionsEvictions);

        cache.put("k1", true, false);
        cache.put("k2", true, false);
        cache.put("k3", true, false);
        cache.put("k4", true, false);
        cache.cleanUp(); // flush pending maintenance so evictions are visible

        assertThat(registry.get(OpaMetrics.METRIC_CACHE_EVICTIONS).tag("cache", "decisions").functionCounter().count())
                .isGreaterThanOrEqualTo(2.0);
        assertThat(registry.get(OpaMetrics.METRIC_CACHE_SIZE).tag("cache", "decisions").gauge().value())
                .isLessThanOrEqualTo(2.0);
    }

    @Test
    void disabledCacheReportsZero()
    {
        MeterRegistry registry = new SimpleMeterRegistry();
        OpaMetrics metrics = new OpaMetrics(registry);
        DecisionCache cache = new DecisionCache(false, 100, 60, 2, List.of());
        metrics.registerCacheGauges("decisions", cache::decisionsSize, cache::decisionsEvictions);

        assertThat(registry.get(OpaMetrics.METRIC_CACHE_SIZE).tag("cache", "decisions").gauge().value()).isZero();
        assertThat(registry.get(OpaMetrics.METRIC_CACHE_EVICTIONS).tag("cache", "decisions").functionCounter().count()).isZero();
    }
}