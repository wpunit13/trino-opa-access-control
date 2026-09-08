package io.opa.trino.metrics;

/**
 * Decision audit log (ARCHITECTURE.md §8.3/§8.4): every decision (allow, deny,
 * fail-closed) is logged with its decision_id, enabling correlation with OPA's
 * decision logs. Injectable so tests can capture decisions without a logging
 * backend; the default implementation writes to SLF4J.
 */
@FunctionalInterface
public interface DecisionLogger
{
    void log(String event);

    static DecisionLogger slf4j()
    {
        org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(OpaMetrics.class);
        return logger::info;
    }
}
