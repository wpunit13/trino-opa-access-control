package io.opa.trino.sql;

/** Raised when OPA-emitted SQL fails structural validation. Maps to fail-closed deny. */
public class SqlValidationException
        extends RuntimeException
{
    public SqlValidationException(String message)
    {
        super(message);
    }

    public SqlValidationException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
