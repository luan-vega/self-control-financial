package com.selfcontrol.app.shared.ratelimit;

import com.selfcontrol.app.shared.error.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.Function;

/**
 * Aplica um {@link RateLimiter} às requisições. A chave vem da função (ex.: id do usuário ou IP);
 * chave nula deixa passar sem contar. Responde 429 com Retry-After e informa o saldo nos headers
 * X-RateLimit-Limit e X-RateLimit-Remaining. Não é @Component: entra só nas cadeias que o usam.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final Function<HttpServletRequest, String> key;
    private final boolean exposeHeaders;

    public RateLimitFilter(RateLimiter limiter, Function<HttpServletRequest, String> key, boolean exposeHeaders) {
        this.limiter = limiter;
        this.key = key;
        this.exposeHeaders = exposeHeaders;
    }

    /** Cada instância conta à parte: com o nome padrão, a segunda da cadeia acharia que já rodou. */
    @Override
    protected String getAlreadyFilteredAttributeName() {
        return RateLimitFilter.class.getName() + "@" + System.identityHashCode(this) + ".FILTERED";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String k = key.apply(request);
        if (k == null) {
            chain.doFilter(request, response);
            return;
        }
        RateLimiter.Decision decision = limiter.tryAcquire(k);
        if (exposeHeaders) {
            response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
            response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
        }
        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            Problems.write(response, HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas requisições. Tente de novo em " + decision.retryAfterSeconds() + " s.");
            return;
        }
        chain.doFilter(request, response);
    }
}
