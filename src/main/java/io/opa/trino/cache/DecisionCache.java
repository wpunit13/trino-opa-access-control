package io.opa.trino.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.List;
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
                    .build();
            volatileDecisions = Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(Math.min(ttlSeconds, 5)))
                    .maximumSize(maxSize)
                    .build();
            negative = Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(negativeTtlSeconds))
                    .maximumSize(maxSize)
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
