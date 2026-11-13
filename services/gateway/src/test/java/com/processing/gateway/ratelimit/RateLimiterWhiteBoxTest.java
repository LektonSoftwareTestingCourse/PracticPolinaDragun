package com.processing.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class RateLimiterWhiteBoxTest {
    private final AtomicLong nanos = new AtomicLong();


    @Test
    void shouldAllowRequestWhenBucketIsNew() {

        var limiter = limiter(1);

        var allowed = limiter.allowRequest("client-a");

        assertThat(allowed).isTrue();
    }


    @Test
    void shouldRejectRequestWhenCapacityIsZero() {

        var limiter = limiter(0);

        var allowed = limiter.allowRequest("client-a");

        assertThat(allowed).isFalse();
    }


    @Test
    void shouldRejectRequestWhenBucketIsExhausted() {

        var limiter = limiter(1);
        limiter.allowRequest("client-a");

        var allowed = limiter.allowRequest("client-a");

        assertThat(allowed).isFalse();
    }


    @Test
    void shouldAllowRequestWhenOneTokenHasRefilled() {

        var limiter = limiter(1);
        limiter.allowRequest("client-a");
        nanos.set(1_000_000_000L);

        var allowed = limiter.allowRequest("client-a");

        assertThat(allowed).isTrue();
    }


    @Test
    void shouldRejectRequestWhenRefilledTokenIsStillFractional() {

        var limiter = limiter(1);
        limiter.allowRequest("client-a");
        nanos.set(999_999_999L);

        var allowed = limiter.allowRequest("client-a");

        assertThat(allowed).isFalse();
    }

    private InMemoryRateLimiter limiter(int capacity) {
        return InMemoryRateLimiter.forTesting(capacity, 1.0, Duration.ofMinutes(10), 100, nanos::get);
    }
}
