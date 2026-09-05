package io.opa.trino;

import io.airlift.configuration.ConfigurationFactory;
import io.opa.trino.cache.CacheKeyCalculator;
import io.opa.trino.cache.DecisionCache;
import io.opa.trino.client.OpaHttpClient;
import io.opa.trino.client.OpaResponseParser;
import io.opa.trino.config.OpaConfig;
import io.opa.trino.marshal.OpaRequestMarshaller;
import io.opa.trino.sql.SqlExpressionValidator;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemAccessControlFactory;

import java.util.List;
import java.util.Map;

/**
 * Factory for the {@code opa-access-control} SystemAccessControl. Reads and validates
 * configuration at startup, failing fast on unknown or invalid keys.
 */
public final class OpaAccessControlFactory
        implements SystemAccessControlFactory
{
    public static final String NAME = "opa-access-control";

    /** Every property key this plugin accepts (ARCHITECTURE.md §9 + the factory name). */
    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
            "access-control.name",
            "opa.endpoint.url",
            "opa.policy.allow.path",
            "opa.policy.row-filters.path",
            "opa.policy.column-masks.path",
            "opa.policy.filter.path",
            "opa.client.timeout-ms",
            "opa.client.max-connections",
            "opa.client.max-connections-per-route",
            "opa.client.retry-max",
            "opa.client.retry-backoff-ms",
            "opa.client.tls.enabled",
            "opa.client.tls.truststore.path",
            "opa.client.auth.token",
            "opa.sql.mode",
            "opa.sql.parser.enabled",
            "opa.sql.allowed-functions",
            "opa.cache.enabled",
            "opa.cache.ttl-seconds",
            "opa.cache.max-size",
            "opa.cache.negative-ttl-seconds",
            "opa.cache.volatile-fields",
            "opa.circuit-breaker.enabled",
            "opa.circuit-breaker.failure-threshold",
            "opa.circuit-breaker.open-duration-ms");

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public SystemAccessControl create(Map<String, String> properties, SystemAccessControlFactory.SystemAccessControlContext context)
    {
        for (String key : properties.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown configuration property for " + NAME + ": " + key);
            }
        }

        ConfigurationFactory configurationFactory = new ConfigurationFactory(properties);
        OpaConfig config = configurationFactory.build(OpaConfig.class);
        config.validate();

        io.opa.trino.client.CircuitBreaker circuitBreaker = new io.opa.trino.client.CircuitBreaker(
                config.getCircuitBreakerFailureThreshold(),
                config.getCircuitBreakerOpenDurationMs());
        OpaHttpClient client = new OpaHttpClient(
                config.getEndpointUrl(),
                config.getTimeoutMs(),
                config.getRetryMax(),
                config.getRetryBackoffMs(),
                config.isCircuitBreakerEnabled() ? circuitBreaker : null);
        OpaResponseParser responseParser = new OpaResponseParser(OpaConfig.SUPPORTED_SCHEMA_VERSION);
        OpaRequestMarshaller marshaller = new OpaRequestMarshaller();
        CacheKeyCalculator cacheKeyCalculator = new CacheKeyCalculator();
        DecisionCache decisionCache = new DecisionCache(
                config.isCacheEnabled(),
                config.getCacheMaxSize(),
                config.getCacheTtlSeconds(),
                config.getNegativeTtlSeconds(),
                config.getVolatileFields());
        SqlExpressionValidator sqlValidator = new SqlExpressionValidator(
                config.isSqlParserEnabled() ? config.getAllowedFunctions() : List.of());

        return new OpaAccessControl(config, client, responseParser, marshaller, cacheKeyCalculator, decisionCache, sqlValidator);
    }
}
