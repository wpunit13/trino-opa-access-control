package io.opa.trino.config;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;

import java.net.URI;
import java.util.List;

/**
 * Configuration for the OPA access control plugin (ARCHITECTURE.md §9).
 * Values are read from access-control.properties via Trino's ConfigurationFactory.
 */
public class OpaConfig
{
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private String endpointUrl;
    private String allowPath = "/v1/data/trino/allow";
    private String rowFiltersPath = "/v1/data/trino/row_filters";
    private String columnMasksPath = "/v1/data/trino/column_masks";
    private String filterPath = "/v1/data/trino/filter";

    private int timeoutMs = 250;
    private int maxConnections = 100;
    private int maxConnectionsPerRoute = 50;
    private int retryMax = 2;
    private int retryBackoffMs = 50;

    private boolean tlsEnabled;
    private String tlsTruststorePath;
    private String authToken;

    private String sqlMode = "passthrough";
    private boolean sqlParserEnabled = true;
    private List<String> allowedFunctions = List.of();

    private boolean cacheEnabled = true;
    private int cacheTtlSeconds = 30;
    private long cacheMaxSize = 50_000;
    private int negativeTtlSeconds = 2;
    private List<String> volatileFields = List.of("source_ip", "catalog_session_properties");

    private boolean circuitBreakerEnabled = true;
    private int circuitBreakerFailureThreshold = 10;
    private int circuitBreakerOpenDurationMs = 10_000;

    @Config("opa.endpoint.url")
    @ConfigDescription("Base URL of the OPA server, e.g. http://127.0.0.1:8181")
    public OpaConfig setEndpointUrl(String endpointUrl)
    {
        this.endpointUrl = endpointUrl;
        return this;
    }

    public String getEndpointUrl()
    {
        return endpointUrl;
    }

    @Config("opa.policy.allow.path")
    public OpaConfig setAllowPath(String allowPath)
    {
        this.allowPath = allowPath;
        return this;
    }

    public String getAllowPath()
    {
        return allowPath;
    }

    @Config("opa.policy.row-filters.path")
    public OpaConfig setRowFiltersPath(String rowFiltersPath)
    {
        this.rowFiltersPath = rowFiltersPath;
        return this;
    }

    public String getRowFiltersPath()
    {
        return rowFiltersPath;
    }

    @Config("opa.policy.column-masks.path")
    public OpaConfig setColumnMasksPath(String columnMasksPath)
    {
        this.columnMasksPath = columnMasksPath;
        return this;
    }

    public String getColumnMasksPath()
    {
        return columnMasksPath;
    }

    @Config("opa.policy.filter.path")
    public OpaConfig setFilterPath(String filterPath)
    {
        this.filterPath = filterPath;
        return this;
    }

    public String getFilterPath()
    {
        return filterPath;
    }

    @Config("opa.client.timeout-ms")
    public OpaConfig setTimeoutMs(int timeoutMs)
    {
        this.timeoutMs = timeoutMs;
        return this;
    }

    public int getTimeoutMs()
    {
        return timeoutMs;
    }

    @Config("opa.client.max-connections")
    public OpaConfig setMaxConnections(int maxConnections)
    {
        this.maxConnections = maxConnections;
        return this;
    }

    public int getMaxConnections()
    {
        return maxConnections;
    }

    @Config("opa.client.max-connections-per-route")
    public OpaConfig setMaxConnectionsPerRoute(int maxConnectionsPerRoute)
    {
        this.maxConnectionsPerRoute = maxConnectionsPerRoute;
        return this;
    }

    public int getMaxConnectionsPerRoute()
    {
        return maxConnectionsPerRoute;
    }

    @Config("opa.client.retry-max")
    public OpaConfig setRetryMax(int retryMax)
    {
        this.retryMax = retryMax;
        return this;
    }

    public int getRetryMax()
    {
        return retryMax;
    }

    @Config("opa.client.retry-backoff-ms")
    public OpaConfig setRetryBackoffMs(int retryBackoffMs)
    {
        this.retryBackoffMs = retryBackoffMs;
        return this;
    }

    public int getRetryBackoffMs()
    {
        return retryBackoffMs;
    }

    @Config("opa.client.tls.enabled")
    public OpaConfig setTlsEnabled(boolean tlsEnabled)
    {
        this.tlsEnabled = tlsEnabled;
        return this;
    }

    public boolean isTlsEnabled()
    {
        return tlsEnabled;
    }

    @Config("opa.client.tls.truststore.path")
    public OpaConfig setTlsTruststorePath(String tlsTruststorePath)
    {
        this.tlsTruststorePath = tlsTruststorePath;
        return this;
    }

    public String getTlsTruststorePath()
    {
        return tlsTruststorePath;
    }

    @Config("opa.client.auth.token")
    public OpaConfig setAuthToken(String authToken)
    {
        this.authToken = authToken;
        return this;
    }

    public String getAuthToken()
    {
        return authToken;
    }

    @Config("opa.sql.mode")
    @ConfigDescription("passthrough (OPA emits raw SQL) or safe (structured descriptors)")
    public OpaConfig setSqlMode(String sqlMode)
    {
        this.sqlMode = sqlMode;
        return this;
    }

    public String getSqlMode()
    {
        return sqlMode;
    }

    @Config("opa.sql.parser.enabled")
    public OpaConfig setSqlParserEnabled(boolean sqlParserEnabled)
    {
        this.sqlParserEnabled = sqlParserEnabled;
        return this;
    }

    public boolean isSqlParserEnabled()
    {
        return sqlParserEnabled;
    }

    @Config("opa.sql.allowed-functions")
    public OpaConfig setAllowedFunctions(String allowedFunctions)
    {
        this.allowedFunctions = List.of(allowedFunctions.split(","));
        return this;
    }

    public List<String> getAllowedFunctions()
    {
        return allowedFunctions;
    }

    @Config("opa.cache.enabled")
    public OpaConfig setCacheEnabled(boolean cacheEnabled)
    {
        this.cacheEnabled = cacheEnabled;
        return this;
    }

    public boolean isCacheEnabled()
    {
        return cacheEnabled;
    }

    @Config("opa.cache.ttl-seconds")
    public OpaConfig setCacheTtlSeconds(int cacheTtlSeconds)
    {
        this.cacheTtlSeconds = cacheTtlSeconds;
        return this;
    }

    public int getCacheTtlSeconds()
    {
        return cacheTtlSeconds;
    }

    @Config("opa.cache.max-size")
    public OpaConfig setCacheMaxSize(long cacheMaxSize)
    {
        this.cacheMaxSize = cacheMaxSize;
        return this;
    }

    public long getCacheMaxSize()
    {
        return cacheMaxSize;
    }

    @Config("opa.cache.negative-ttl-seconds")
    public OpaConfig setNegativeTtlSeconds(int negativeTtlSeconds)
    {
        this.negativeTtlSeconds = negativeTtlSeconds;
        return this;
    }

    public int getNegativeTtlSeconds()
    {
        return negativeTtlSeconds;
    }

    @Config("opa.cache.volatile-fields")
    public OpaConfig setVolatileFields(String volatileFields)
    {
        this.volatileFields = List.of(volatileFields.split(","));
        return this;
    }

    public List<String> getVolatileFields()
    {
        return volatileFields;
    }

    @Config("opa.circuit-breaker.enabled")
    public OpaConfig setCircuitBreakerEnabled(boolean circuitBreakerEnabled)
    {
        this.circuitBreakerEnabled = circuitBreakerEnabled;
        return this;
    }

    public boolean isCircuitBreakerEnabled()
    {
        return circuitBreakerEnabled;
    }

    @Config("opa.circuit-breaker.failure-threshold")
    public OpaConfig setCircuitBreakerFailureThreshold(int circuitBreakerFailureThreshold)
    {
        this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold;
        return this;
    }

    public int getCircuitBreakerFailureThreshold()
    {
        return circuitBreakerFailureThreshold;
    }

    @Config("opa.circuit-breaker.open-duration-ms")
    public OpaConfig setCircuitBreakerOpenDurationMs(int circuitBreakerOpenDurationMs)
    {
        this.circuitBreakerOpenDurationMs = circuitBreakerOpenDurationMs;
        return this;
    }

    public int getCircuitBreakerOpenDurationMs()
    {
        return circuitBreakerOpenDurationMs;
    }

    /** Fail fast on invalid configuration at startup. */
    public void validate()
    {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new IllegalArgumentException("opa.endpoint.url is required");
        }
        try {
            URI uri = URI.create(endpointUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("opa.endpoint.url must be an http(s) URL: " + endpointUrl);
            }
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid opa.endpoint.url: " + endpointUrl, e);
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("opa.client.timeout-ms must be > 0");
        }
        if (maxConnections <= 0 || maxConnectionsPerRoute <= 0 || maxConnectionsPerRoute > maxConnections) {
            throw new IllegalArgumentException("opa.client.max-connections(-per-route) must be > 0 and per-route <= max");
        }
        if (retryMax < 0 || retryBackoffMs < 0) {
            throw new IllegalArgumentException("opa.client.retry-max and retry-backoff-ms must be >= 0");
        }
        if (!"passthrough".equalsIgnoreCase(sqlMode) && !"safe".equalsIgnoreCase(sqlMode)) {
            throw new IllegalArgumentException("opa.sql.mode must be 'passthrough' or 'safe': " + sqlMode);
        }
        if (cacheTtlSeconds < 0 || negativeTtlSeconds < 0 || cacheMaxSize < 0) {
            throw new IllegalArgumentException("cache ttl/size settings must be >= 0");
        }
        if (tlsEnabled && (tlsTruststorePath == null || tlsTruststorePath.isBlank())) {
            throw new IllegalArgumentException("opa.client.tls.truststore.path is required when TLS is enabled");
        }
    }
}
