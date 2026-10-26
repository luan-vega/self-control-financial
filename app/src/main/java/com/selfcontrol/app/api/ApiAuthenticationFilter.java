package com.selfcontrol.app.api;

import com.selfcontrol.app.apikey.ApiKeyService;
import com.selfcontrol.app.shared.security.AppUser;
import com.selfcontrol.app.shared.security.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Autentica a API pública pelo header {@code Authorization: Bearer ...}, sem sessão nem cookie:
 * <ul>
 *     <li>chave de API ({@code scfk_...}), criada em Configurações → Chaves de API;</li>
 *     <li>ID token do Google (JWT), para apps nativos que fazem login com Google, quando
 *     app.api.google-client-ids está configurado.</li>
 * </ul>
 * O principal ganha SCOPE_read e, se a chave não for somente leitura, SCOPE_write. Com credencial
 * válida a requisição roda como o dono, então o RLS vale igual às telas.
 */
public class ApiAuthenticationFilter extends OncePerRequestFilter {

    /** Quem a credencial identifica. */
    public record Caller(UUID userId, String email, Role role, boolean readOnly) {
    }

    private final ApiKeyService keys;
    private final GoogleIdTokens google;

    public ApiAuthenticationFilter(ApiKeyService keys, GoogleIdTokens google) {
        this.keys = keys;
        this.google = google;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = bearer(request);
        if (token != null) {
            resolve(token).ifPresent(caller -> {
                List<GrantedAuthority> authorities = new ArrayList<>(AuthorityUtils.createAuthorityList(caller.role().authorities()));
                authorities.add(Scopes.READ);
                if (!caller.readOnly()) {
                    authorities.add(Scopes.WRITE);
                }
                AppUser principal = new AppUser(caller.userId(), caller.email(), null, authorities);
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }

    private Optional<Caller> resolve(String token) {
        if (token.startsWith(ApiKeyService.PREFIX)) {
            return keys.authenticate(token).map(o -> new Caller(o.userId(), o.email(), o.role(), o.readOnly()));
        }
        if (google != null && GoogleIdTokens.looksLikeJwt(token)) {
            return google.authenticate(token);
        }
        return Optional.empty();
    }

    private static String bearer(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authorization.substring(7).strip();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
