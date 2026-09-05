package io.opa.trino.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §6.3: circuit breaker open/half-open/close transitions. */
class CircuitBreakerTest
{
    @Test
    void staysClosedUnderFailuresBelowThreshold()
    {
        CircuitBreaker breaker = new CircuitBreaker(3, 10_000);
        breaker.recordFailure();
        breaker.recordFailure();
        assertThatCode(breaker::acquire).doesNotThrowAnyException();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void opensAfterConsecutiveFailuresAndFailsFast()
    {
        CircuitBreaker breaker = new CircuitBreaker(2, 10_000);
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(breaker::acquire)
                .isInstanceOf(OpaClientException.class)
                .hasMessageContaining("circuit breaker is open");
    }

    @Test
    void halfOpensAfterOpenDurationThenClosesOnSuccess()
    {
        AtomicLong now = new AtomicLong(0); // nanoseconds
        CircuitBreaker breaker = new CircuitBreaker(1, 1_000, now::get);

        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(breaker::acquire).isInstanceOf(OpaClientException.class);

        now.set(1_000_000_000L + 1); // 1000ms elapsed
        assertThatCode(breaker::acquire).doesNotThrowAnyException(); // probe admitted (HALF_OPEN)
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThatCode(breaker::acquire).doesNotThrowAnyException();
    }

    @Test
    void halfOpenProbeFailureReOpens()
    {
        AtomicLong now = new AtomicLong(0);
        CircuitBreaker breaker = new CircuitBreaker(1, 1_000, now::get);

        breaker.recordFailure();
        now.set(2_000_000_000L); // past open duration
        assertThatCode(breaker::acquire).doesNotThrowAnyException(); // half-open probe

        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        // still within the new open window → fail fast again
        now.set(2_500_000_000L);
        assertThatThrownBy(breaker::acquire).isInstanceOf(OpaClientException.class);
    }

    @Test
    void successResetsFailureCount()
    {
        CircuitBreaker breaker = new CircuitBreaker(2, 10_000);
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
