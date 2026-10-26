package com.selfcontrol.app.shared.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;
import java.util.function.Supplier;

/** Executa um trecho como um usuário fora de uma requisição (seeders, jobs, testes). */
public final class RunAs {

    private RunAs() {
    }

    public static <T> T user(UUID userId, String email, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        AppUser principal = new AppUser(userId, email, null);
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
