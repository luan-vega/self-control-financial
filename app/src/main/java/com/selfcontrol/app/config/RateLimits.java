package com.selfcontrol.app.config;

import com.selfcontrol.app.shared.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Limites de requisições por minuto (app.rate-limit.*). */
@Component
class RateLimits {

    private final RateLimiter api;
    private final RateLimiter ip;
    private final RateLimiter webhook;
    private final RateLimiter auth;

    RateLimits(@Value("${app.rate-limit.api-per-minute:100}") int api,
               @Value("${app.rate-limit.ip-per-minute:300}") int ip,
               @Value("${app.rate-limit.webhook-per-minute:60}") int webhook,
               @Value("${app.rate-limit.auth-per-minute:10}") int auth) {
        this.api = new RateLimiter(api);
        this.ip = new RateLimiter(ip);
        this.webhook = new RateLimiter(webhook);
        this.auth = new RateLimiter(auth);
    }

    /** API pública, por usuário (todas as chaves do usuário somam). */
    RateLimiter api() {
        return api;
    }

    /** API pública, por IP, antes da autenticação. */
    RateLimiter ip() {
        return ip;
    }

    /** Webhook dos Atalhos, por usuário. */
    RateLimiter webhook() {
        return webhook;
    }

    /** Login, cadastro e recuperação de senha, por IP. */
    RateLimiter auth() {
        return auth;
    }
}
