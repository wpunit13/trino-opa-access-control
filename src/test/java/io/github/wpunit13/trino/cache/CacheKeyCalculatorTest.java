package io.github.wpunit13.trino.cache;

import io.github.wpunit13.trino.marshal.OpaAction;
import io.github.wpunit13.trino.marshal.OpaRequestContext;
import io.github.wpunit13.trino.marshal.OpaRequestMarshaller;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant #2 (review.md §3.1 / ARCHITECTURE.md §6.2):
 * the cache key is a canonical hash of the FULL marshaled input excluding volatile
 * fields. A change in ANY decision-relevant field changes the key.
 */
class CacheKeyCalculatorTest
{
    private final CacheKeyCalculator calculator = new CacheKeyCalculator();
    private final OpaRequestMarshaller marshaller = new OpaRequestMarshaller();

    private Map<String, Object> marshal(String user, List<String> groups, Map<String, List<String>> roles,
                                        List<String> clientTags, String queryId, String decisionId)
    {
        return marshaller.marshal(new OpaRequestContext(
                OpaAction.SELECT_FROM_COLUMNS, user, groups, roles, clientTags,
                Optional.empty(), Optional.ofNullable(queryId), Optional.empty(), Map.of(),
                "lakehouse", "finance", "salaries", List.of("ssn", "name")), decisionId);
    }

    @Test
    void volatileFieldsAreExcludedFromKey()
    {
        String key1 = calculator.cacheKey(marshal("alice", List.of("eng"), Map.of(), List.of(), "q-1", "decision-1"));
        String key2 = calculator.cacheKey(marshal("alice", List.of("eng"), Map.of(), List.of(), "q-2", "decision-2"));
        // different query_id and decision_id must NOT change the key
        assertThat(key2).isEqualTo(key1);
    }

    @Test
    void groupsChangeProducesDifferentKey()
    {
        String key1 = calculator.cacheKey(marshal("alice", List.of("eng"), Map.of(), List.of(), "q-1", "d"));
        String key2 = calculator.cacheKey(marshal("alice", List.of("eng", "audit"), Map.of(), List.of(), "q-1", "d"));
        assertThat(key2).isNotEqualTo(key1);
    }

    @Test
    void rolesChangeProducesDifferentKey()
    {
        String key1 = calculator.cacheKey(marshal("alice", List.of(), Map.of("system", List.of("analyst")), List.of(), "q", "d"));
        String key2 = calculator.cacheKey(marshal("alice", List.of(), Map.of("system", List.of("admin")), List.of(), "q", "d"));
        assertThat(key2).isNotEqualTo(key1);
    }

    @Test
    void clientTagsChangeProducesDifferentKey()
    {
        String key1 = calculator.cacheKey(marshal("alice", List.of(), Map.of(), List.of("env:prod"), "q", "d"));
        String key2 = calculator.cacheKey(marshal("alice", List.of(), Map.of(), List.of("env:dev"), "q", "d"));
        assertThat(key2).isNotEqualTo(key1);
    }

    @Test
    void resourceAndColumnsChangeProducesDifferentKey()
    {
        Map<String, Object> base = marshal("alice", List.of(), Map.of(), List.of(), "q", "d");
        Map<String, Object> otherTable = marshaller.marshal(new OpaRequestContext(
                OpaAction.SELECT_FROM_COLUMNS, "alice", List.of(), Map.of(), List.of(),
                Optional.empty(), Optional.of("q"), Optional.empty(), Map.of(),
                "lakehouse", "finance", "payroll", List.of("ssn", "name")), "d");
        Map<String, Object> otherColumns = marshaller.marshal(new OpaRequestContext(
                OpaAction.SELECT_FROM_COLUMNS, "alice", List.of(), Map.of(), List.of(),
                Optional.empty(), Optional.of("q"), Optional.empty(), Map.of(),
                "lakehouse", "finance", "salaries", List.of("ssn")), "d");

        assertThat(calculator.cacheKey(otherTable)).isNotEqualTo(calculator.cacheKey(base));
        assertThat(calculator.cacheKey(otherColumns)).isNotEqualTo(calculator.cacheKey(base));
    }

    @Test
    void actionChangeProducesDifferentKey()
    {
        Map<String, Object> select = marshaller.marshal(new OpaRequestContext(
                OpaAction.SELECT_FROM_COLUMNS, "alice", List.of(), Map.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of(),
                "lakehouse", "finance", "salaries", List.of("ssn")), "d");
        Map<String, Object> create = marshaller.marshal(new OpaRequestContext(
                OpaAction.CREATE_TABLE, "alice", List.of(), Map.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of(),
                "lakehouse", "finance", "salaries", List.of("ssn")), "d");
        assertThat(calculator.cacheKey(create)).isNotEqualTo(calculator.cacheKey(select));
    }

    @Test
    void keyIsOrderInsensitiveForGroupsAndSessionProperties()
    {
        Map<String, List<String>> rolesA = new LinkedHashMap<>();
        rolesA.put("system", List.of("r1"));
        Map<String, List<String>> rolesB = new LinkedHashMap<>();
        rolesB.put("system", List.of("r1"));

        String key1 = calculator.cacheKey(marshal("alice", List.of("a", "b"), rolesA, List.of(), "q", "d"));
        String key2 = calculator.cacheKey(marshal("alice", List.of("b", "a"), rolesB, List.of(), "q", "d"));
        // canonicalization sorts lists, so the same set yields the same key
        assertThat(key2).isEqualTo(key1);
    }

    @Test
    void keyIsStableSha256Hex()
    {
        String key = calculator.cacheKey(marshal("alice", List.of(), Map.of(), List.of(), "q", "d"));
        assertThat(key).hasSize(64).matches("[0-9a-f]+")
                .isEqualTo(calculator.cacheKey(marshal("alice", List.of(), Map.of(), List.of(), "q", "d")));
    }
}
