package io.github.wpunit13.trino;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Factory behavior: name, unknown-key fail fast, construction via ConfigurationFactory. */
class OpaAccessControlFactoryTest
{
    private final OpaAccessControlFactory factory = new OpaAccessControlFactory();

    @Test
    void factoryNameMatchesDocumentation()
    {
        assertThat(factory.getName()).isEqualTo("opa-access-control");
    }

    @Test
    void createsAccessControlFromValidProperties()
    {
        Map<String, String> properties = new HashMap<>();
        properties.put("access-control.name", "opa-access-control");
        properties.put("opa.endpoint.url", "http://127.0.0.1:8181");
        properties.put("opa.cache.ttl-seconds", "15");
        assertThat(factory.create(properties, null)).isNotNull();
    }

    @Test
    void unknownKeysFailFast()
    {
        Map<String, String> properties = new HashMap<>();
        properties.put("access-control.name", "opa-access-control");
        properties.put("opa.endpoint.url", "http://127.0.0.1:8181");
        properties.put("opa.unknown.key", "value");
        assertThatThrownBy(() -> factory.create(properties, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opa.unknown.key");
    }

    @Test
    void missingEndpointFailsFast()
    {
        Map<String, String> properties = new HashMap<>();
        properties.put("access-control.name", "opa-access-control");
        assertThatThrownBy(() -> factory.create(properties, null))
                .isInstanceOf(RuntimeException.class);
    }
}
