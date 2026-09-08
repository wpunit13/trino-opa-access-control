package io.github.wpunit13.trino.client;

/** Raised when an OPA response is malformed or uses an unsupported schema_version. */
public class OpaResponseException
        extends RuntimeException
{
    public OpaResponseException(String message)
    {
        super(message);
    }
}
