package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CircuitBreakerWhiteBoxTest {
    private final Clock clock = mock(Clock.class);


    @Test
    void shouldAllowRequestWhenCircuitIsClosed() {

        var breaker = breaker();

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isTrue();
    }


    @Test
    void shouldAllowRequestWhenFailureThresholdIsNotReached() {

        var breaker = breaker();
        breaker.recordFailure("switch");

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isTrue();
    }


    @Test
    void shouldRejectRequestWhenCircuitIsOpen() {

        var breaker = openBreaker();

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isFalse();
    }


    @Test
    void shouldAllowProbeWhenOpenDurationExpires() {

        var breaker = openBreaker();
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(10));

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isTrue();
    }


    @Test
    void shouldRejectSecondProbeWhenFirstProbeIsInProgress() {

        var breaker = halfOpenBreaker();

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isFalse();
    }


    @Test
    void shouldAllowRequestWhenSuccessfulProbeIsFollowedByOneFailure() {

        var breaker = halfOpenBreaker();
        breaker.recordSuccess("switch");
        breaker.recordFailure("switch");

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isTrue();
    }


    @Test
    void shouldRejectRequestsWhenHalfOpenProbeFails() {

        var breaker = halfOpenBreaker();
        breaker.recordFailure("switch");

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isFalse();
    }

    private InMemoryCircuitBreaker breaker() {
        when(clock.instant()).thenReturn(Instant.EPOCH);
        return InMemoryCircuitBreaker.forTesting(Duration.ofSeconds(10), 2, clock);
    }

    private InMemoryCircuitBreaker openBreaker() {
        var breaker = breaker();
        breaker.recordFailure("switch");
        breaker.recordFailure("switch");
        return breaker;
    }

    private InMemoryCircuitBreaker halfOpenBreaker() {
        var breaker = openBreaker();
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(10));
        breaker.allowRequest("switch");
        return breaker;
    }
}
