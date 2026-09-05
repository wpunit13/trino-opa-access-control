package io.opa.trino;

import io.trino.spi.Plugin;
import io.trino.spi.security.SystemAccessControlFactory;

import java.util.List;

/** Plugin entrypoint registered with Trino's plugin loader. */
public final class OpaAccessControlPlugin
        implements Plugin
{
    @Override
    public Iterable<SystemAccessControlFactory> getSystemAccessControlFactories()
    {
        return List.of(new OpaAccessControlFactory());
    }
}
