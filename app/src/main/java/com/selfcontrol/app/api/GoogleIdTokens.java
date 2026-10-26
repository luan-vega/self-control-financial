package com.selfcontrol.app.api;

import com.selfcontrol.app.shared.security.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Aceita o ID token que o app nativo recebe ao fazer login com Google. O token é validado pela
 * assinatura (chaves públicas do Google), emissor, validade e audiência (os client ids dos apps).
 * A conta precisa já estar ligada a esse login Google (feito uma vez pelo app web), então um token
 * Google qualquer não cria contas pela API.
 */
public class GoogleIdTokens {

    static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final JwtDecoder decoder;
    private final JdbcClient jdbc;

    public GoogleIdTokens(JwtDecoder decoder, JdbcClient jdbc) {
        this.decoder = decoder;
        this.jdbc = jdbc;
    }

    static boolean looksLikeJwt(String token) {
        return token.length() < 4096 && token.chars().filter(c -> c == '.').count() == 2;
    }

    Optional<ApiAuthenticationFilter.Caller> authenticate(String token) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT u.id, u.email, u.role
                          FROM user_identity i
                          JOIN app_user u ON u.id = i.user_id
                         WHERE i.provider = 'google' AND i.subject = :subject AND u.active
                        """)
                .param("subject", jwt.getSubject())
                .query((rs, n) -> new ApiAuthenticationFilter.Caller(rs.getObject("id", UUID.class), rs.getString("email"),
                        Role.valueOf(rs.getString("role")), false))
                .optional();
    }

    /** Emissor do Google e audiência igual a um dos client ids configurados. */
    public static OAuth2TokenValidatorResult validate(Jwt jwt, Set<String> clientIds) {
        String issuer = jwt.getClaimAsString("iss");
        if (issuer == null || !ISSUERS.contains(issuer)) {
            return OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error(
                    "invalid_token", "Emissor inválido", null));
        }
        if (jwt.getAudience() == null || jwt.getAudience().stream().noneMatch(clientIds::contains)) {
            return OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error(
                    "invalid_token", "Audiência inválida", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
