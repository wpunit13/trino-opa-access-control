package io.opa.trino.client;

/**
 * Minimal hand-rolled circuit breaker (ARCHITECTURE.md §6.3): after
 * {@code failureThreshold} consecutive failures the breaker opens and
 * {@link #acquire()} fails fast (so a degraded OPA does not cascade latency into
 * query planning). After {@code openDurationMs} the breaker half-opens and admits
 * a single probe request; success closes it, failure re-opens it.
 *
 * Thread-safe: guarded by a monitor, matching the coarse granularity of the PDP.
 */
public final class CircuitBreaker
{
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openDurationMs;
    private final java.util.function.LongSupplier clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAtMs;

    public CircuitBreaker(int failureThreshold, long openDurationMs)
    {
        this(failureThreshold, openDurationMs, System::nanoTime);
    }

    /** Visible for testing: inject a controllable clock (nanoseconds). */
    public CircuitBreaker(int failureThreshold, long openDurationMs, java.util.function.LongSupplier clock)
    {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be > 0");
        }
        if (openDurationMs < 0) {
            throw new IllegalArgumentException("openDurationMs must be >= 0");
        }
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
        this.clock = clock;
    }

    /**
     * Must be called before dispatching a request. Throws (fail fast) while open.
     */
    public synchronized void acquire()
    {
        if (state == State.OPEN) {
            if (elapsedSinceOpen() >= openDurationMs) {
                state = State.HALF_OPEN;
            }
            else {
                throw new OpaClientException("OPA circuit breaker is open (failing fast)");
            }
        }
        // CLOSED and HALF_OPEN admit the request.
    }

    public synchronized void recordSuccess()
    {
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    public synchronized void recordFailure()
    {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    public synchronized State state()
    {
        if (state == State.OPEN && elapsedSinceOpen() >= openDurationMs) {
            state = State.HALF_OPEN;
        }
        return state;
    }

    private void open()
    {
        state = State.OPEN;
        openedAtMs = clock.getAsLong();
    }

    private long elapsedSinceOpen()
    {
        return (clock.getAsLong() - openedAtMs) / 1_000_000;
    }
}
