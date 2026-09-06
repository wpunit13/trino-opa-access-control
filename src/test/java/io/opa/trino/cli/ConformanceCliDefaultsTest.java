package io.opa.trino.cli;

import io.opa.trino.config.OpaConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 6: the CLI's defaults must match the plugin's OpaConfig defaults at
 * implementation time — the CLI cannot load OpaConfig (airlift stays out of the
 * shaded CLI jar), so this test pins the parity instead.
 */
class ConformanceCliDefaultsTest
{
    @Test
    void cliDefaultsMatchPluginConfigDefaults()
    {
        OpaConfig config = new OpaConfig();
        assertThat(ConformanceCli.DEFAULT_SQL_MODE).isEqualTo(config.getSqlMode());
        assertThat(ConformanceCli.DEFAULT_MAX_IN_CLAUSE_SIZE).isEqualTo(config.getMaxInClauseSize());
        assertThat(ConformanceCli.DEFAULT_SCHEMA_VERSION).isEqualTo(OpaConfig.SUPPORTED_SCHEMA_VERSION);
    }
}
