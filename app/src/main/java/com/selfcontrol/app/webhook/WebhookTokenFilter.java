package com.selfcontrol.app.webhook;

import com.selfcontrol.app.shared.security.AppUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Autentica o webhook pelo token secreto ({@code Authorization: Bearer scf_...} ou {@code X-Webhook-Token}).
 * Com token válido, a requisição roda como o dono do token, então o RLS vale igual às telas.
 * Não é um @Component de propósito: senão o Spring Boot o registraria para todas as requisições.
 */
public class WebhookTokenFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Webhook-Token";

    private final WebhookTokenService tokens;

    public WebhookTokenFilter(WebhookTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = token(request);
        if (token != null) {
            tokens.authenticate(token).ifPresent(owner -> {
                AppUser principal = new AppUser(owner.userId(), owner.email(), null);
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }

    private static String token(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).strip();
        }
        String header = request.getHeader(TOKEN_HEADER);
        return header == null ? null : header.strip();
    }
}
