package io.opa.trino.cache;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Invariant #2 / review.md §3.1 / ARCHITECTURE.md §6.2:
 * the cache key is a canonical SHA-256 hash of the FULL marshaled input with stable
 * field ordering, EXCLUDING volatile fields (decision_id, session.query_id, and any
 * timestamps). Never a hand-picked (user, catalog, schema, table, column, action) subset.
 */
public final class CacheKeyCalculator
{
    public static final String SHA_256 = "SHA-256";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Computes the canonical hash for the marshaled input map produced by
     * {@code OpaRequestMarshaller}.
     */
    public String cacheKey(Map<String, Object> marshaledInput)
    {
        Map<String, Object> canonical = canonicalize(marshaledInput);
        try {
            byte[] json = mapper.writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return toHex(digest.digest(json));
        }
        catch (Exception e) {
            // Jackson serialization of Maps/strings/primitives cannot fail in practice;
            // propagate as a runtime error so callers fail closed.
            throw new IllegalStateException("Unable to compute cache key", e);
        }
    }

    /**
     * Builds the canonical (volatile-free) representation:
     * - drops decision_id
     * - drops session.query_id and any session timestamp-like fields
     * - sorts all lists and map keys for stable ordering
     */
    Map<String, Object> canonicalize(Map<String, Object> input)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            if (key.equals("decision_id")) {
                continue;
            }
            out.put(key, canonicalValue(entry.getValue()));
        }

        // Drop volatile session fields
        Object session = out.get("session");
        if (session instanceof Map<?, ?> sessionMap) {
            Map<String, Object> canonicalSession = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : new TreeMap<Object, Object>(sessionMap).entrySet()) {
                String field = String.valueOf(entry.getKey());
                if (field.equals("query_id") || field.toLowerCase().contains("timestamp") || field.toLowerCase().contains("_time")) {
                    continue;
                }
                canonicalSession.put(field, canonicalValue(entry.getValue()));
            }
            out.put("session", canonicalSession);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object canonicalValue(Object value)
    {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : new TreeMap<Object, Object>(map).entrySet()) {
                out.put(String.valueOf(entry.getKey()), canonicalValue(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(canonicalValue(item));
            }
            out.sort((a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
            return out;
        }
        return value;
    }

    public static String toHex(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
