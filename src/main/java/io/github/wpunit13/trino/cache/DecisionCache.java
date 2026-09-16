package io.github.wpunit13.trino.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Caffeine-backed decision caches (ARCHITECTURE.md §6.2 / §9):
 * - a main decision cache (long TTL),
 * - a short-TTL cache for decisions that depend on user-configured volatile fields,
 * - a separate short-TTL negative cache so failures are not hammered repeatedly.
 */
public final class DecisionCache
{
    /** Marker stored in the negative cache; always maps to a deny. */
    public record NegativeEntry(String reason) {}

    private final Cache<String, Object> decisions;
    private final Cache<String, Object> volatileDecisions;
    private final Cache<String, NegativeEntry> negative;
    private final java.util.Set<String> volatileFieldNames;

    public DecisionCache(boolean enabled, long maxSize, int ttlSeconds, int negativeTtlSeconds, java.util.List<String> volatileFields)
    {
        volatileFieldNames = java.util.Set.copyOf(volatileFields);
        if (enabled) {
            decisions = Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                    .maximumSize(maxSize)
                    .recordStats()
                    .build();
            volatileDecisions = Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(Math.min(ttlSeconds, 5)))
                    .maximumSize(maxSize)
                    .recordStats()
                    .build();
            negative = Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(negativeTtlSeconds))
                    .maximumSize(maxSize)
                    .recordStats()
                    .build();
        }
        else {
            decisions = null;
            volatileDecisions = null;
            negative = null;
        }
    }

    public Optional<Object> get(String key, boolean volatileDecision)
    {
        Cache<String, Object> cache = volatileDecision ? volatileDecisions : decisions;
        return cache == null ? Optional.empty() : Optional.ofNullable(cache.getIfPresent(key));
    }

    public void put(String key, Object value, boolean volatileDecision)
    {
        Cache<String, Object> cache = volatileDecision ? volatileDecisions : decisions;
        if (cache != null) {
            cache.put(key, value);
        }
    }

    public Optional<NegativeEntry> getNegative(String key)
    {
        return negative == null ? Optional.empty() : Optional.ofNullable(negative.getIfPresent(key));
    }

    public void putNegative(String key, String reason)
    {
        if (negative != null) {
            negative.put(key, new NegativeEntry(reason));
        }
    }

    // ------------------------------------------------------------------
    // D5 (ROADMAP.md M7): cache size + eviction gauges (§8.4). Caffeine's
    // estimatedSize() is approximate; evictionCount() is cumulative (the
    // monitoring backend derives the rate). All return 0 when caching is off.
    // ------------------------------------------------------------------

    public long decisionsSize()
    {
        return decisions == null ? 0 : decisions.estimatedSize();
    }

    public long volatileDecisionsSize()
    {
        return volatileDecisions == null ? 0 : volatileDecisions.estimatedSize();
    }

    public long negativeSize()
    {
        return negative == null ? 0 : negative.estimatedSize();
    }

    public long decisionsEvictions()
    {
        return decisions == null ? 0 : decisions.stats().evictionCount();
    }

    public long volatileDecisionsEvictions()
    {
        return volatileDecisions == null ? 0 : volatileDecisions.stats().evictionCount();
    }

    public long negativeEvictions()
    {
        return negative == null ? 0 : negative.stats().evictionCount();
    }

    /** Triggers Caffeine maintenance (used by tests and operators to flush pending evictions). */
    public void cleanUp()
    {
        if (decisions != null) {
            decisions.cleanUp();
        }
        if (volatileDecisions != null) {
            volatileDecisions.cleanUp();
        }
        if (negative != null) {
            negative.cleanUp();
        }
    }

    /**
     * A decision is "volatile" if the marshaled input populates any user-configured
     * volatile field (e.g. source_ip, catalog_session_properties). Those decisions get
     * a much shorter TTL per §6.2.
     */
    public boolean isVolatileDecision(java.util.Map<String, Object> input)
    {
        for (String field : volatileFieldNames) {
            Object value = lookup(input, field.split("\\."));
            if (value != null && !isEmptyCollection(value) && !isEmptyMap(value)) {
                return true;
            }
        }
        return false;
    }

    private static Object lookup(Map<String, Object> map, String[] path)
    {
        Object current = map;
        for (String part : path) {
            if (!(current instanceof Map<?, ?> m)) {
                return null;
            }
            current = m.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static boolean isEmptyCollection(Object value)
    {
        return value instanceof java.util.Collection<?> c && c.isEmpty();
    }

    private static boolean isEmptyMap(Object value)
    {
        return value instanceof java.util.Map<?, ?> m && m.isEmpty();
    }
}
