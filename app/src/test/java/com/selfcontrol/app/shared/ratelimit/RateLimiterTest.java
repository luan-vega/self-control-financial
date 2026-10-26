package com.selfcontrol.app.shared.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong();

    @Test
    void allowsUpToTheLimitThenRefillsOverTheMinute() {
        RateLimiter limiter = new RateLimiter(3, now::get);

        assertThat(limiter.tryAcquire("a").remaining()).isEqualTo(2);
        assertThat(limiter.tryAcquire("a").remaining()).isEqualTo(1);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        RateLimiter.Decision denied = limiter.tryAcquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 21L);

        // Outra chave tem o próprio balde
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();

        // 20 s depois volta uma ficha (3 por minuto)
        now.addAndGet(TimeUnit.SECONDS.toNanos(20));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        // Nunca acumula mais que o limite
        now.addAndGet(TimeUnit.MINUTES.toNanos(10));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.perMinute()).isEqualTo(3);
    }

    @Test
    void discardsIdleBucketsWhenTheMapGrows() {
        RateLimiter limiter = new RateLimiter(1, now::get);
        for (int i = 0; i < 20_001; i++) {
            limiter.tryAcquire("k" + i);
        }
        now.addAndGet(TimeUnit.MINUTES.toNanos(2));
        // Com os baldes cheios de novo, a limpeza os remove e a chave antiga recomeça do zero
        assertThat(limiter.tryAcquire("k0").allowed()).isTrue();
    }

    @Test
    void rejectsInvalidLimit() {
        assertThatThrownBy(() -> new RateLimiter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
