package io.opa.trino.client;

/**
 * Raised on any OPA communication/validation failure. Must always be translated
 * into a fail-closed {@code AccessDeniedException} by the SPI layer.
 */
public class OpaClientException
        extends RuntimeException
{
    public OpaClientException(String message)
    {
        super(message);
    }

    public OpaClientException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
