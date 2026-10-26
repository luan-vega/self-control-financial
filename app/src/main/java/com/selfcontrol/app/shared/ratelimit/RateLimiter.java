package com.selfcontrol.app.shared.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Token bucket em memória: cada chave (usuário, IP) tem até {@code perMinute} fichas, repostas
 * continuamente ao longo do minuto. Vale por instância da app: com várias réplicas, o limite
 * efetivo é multiplicado pelo número de réplicas (ver docs/deployment.md). Proteção contra DDoS
 * de verdade fica na borda (CDN/ingress); isto protege a app e o banco de abuso por usuário.
 */
public class RateLimiter {

    /** Resultado de uma tentativa. retryAfterSeconds só faz sentido quando não foi permitido. */
    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    }

    private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);
    /** Acima disso, os buckets cheios (inativos) são descartados para a memória não crescer sem fim. */
    private static final int CLEANUP_THRESHOLD = 20_000;

    private final int perMinute;
    private final LongSupplier clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute) {
        this(perMinute, System::nanoTime);
    }

    RateLimiter(int perMinute, LongSupplier clock) {
        if (perMinute <= 0) {
            throw new IllegalArgumentException("perMinute deve ser positivo");
        }
        this.perMinute = perMinute;
        this.clock = clock;
    }

    public int perMinute() {
        return perMinute;
    }

    public Decision tryAcquire(String key) {
        long now = clock.getAsLong();
        if (buckets.size() > CLEANUP_THRESHOLD) {
            buckets.values().removeIf(b -> b.isFull(now));
        }
        return buckets.computeIfAbsent(key, k -> new Bucket(perMinute, now)).take(now);
    }

    private final class Bucket {

        private double tokens;
        private long updatedAt;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.updatedAt = now;
        }

        synchronized Decision take(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, perMinute, (int) Math.floor(tokens), 0);
            }
            double nanosPerToken = (double) MINUTE / perMinute;
            long wait = (long) Math.ceil((1 - tokens) * nanosPerToken);
            return new Decision(false, perMinute, 0, Math.max(1, TimeUnit.NANOSECONDS.toSeconds(wait) + 1));
        }

        synchronized boolean isFull(long now) {
            refill(now);
            return tokens >= perMinute;
        }

        private void refill(long now) {
            long elapsed = now - updatedAt;
            if (elapsed > 0) {
                tokens = Math.min(perMinute, tokens + (double) elapsed * perMinute / MINUTE);
                updatedAt = now;
            }
        }
    }
}
