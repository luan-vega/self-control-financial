package com.selfcontrol.app.webhook;

import com.selfcontrol.app.shared.security.UserContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Token secreto do webhook dos Atalhos (um por usuário). O banco guarda só o SHA-256: quem lê o
 * banco não consegue chamar o webhook. Gerar de novo invalida o anterior.
 */
@Service
@Transactional
public class WebhookTokenService {

    /** Dono do token, para autenticar a chamada como esse usuário. */
    public record Owner(UUID userId, String email) {
    }

    static final String PREFIX = "scf_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final UserContext users;

    WebhookTokenService(JdbcClient jdbc, UserContext users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    /** Gera um token novo para o usuário logado e devolve o valor completo (só aparece esta vez). */
    public String rotate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                        INSERT INTO webhook_token (user_id, token_hash, token_hint)
                        VALUES (:userId, :hash, :hint)
                        ON CONFLICT (user_id) DO UPDATE
                           SET token_hash = excluded.token_hash, token_hint = excluded.token_hint,
                               created_at = now(), last_used_at = NULL
                        """)
                .param("userId", users.current().id())
                .param("hash", hash(token))
                .param("hint", token.substring(token.length() - 4))
                .update();
        return token;
    }

    @Transactional(readOnly = true)
    public Optional<WebhookToken> current() {
        return jdbc.sql("""
                        SELECT token_hint, created_at, last_used_at FROM webhook_token
                         WHERE user_id = app_current_user_id()
                        """)
                .query((rs, n) -> new WebhookToken(rs.getString("token_hint"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("last_used_at"))))
                .optional();
    }

    public void revoke() {
        jdbc.sql("DELETE FROM webhook_token WHERE user_id = app_current_user_id()").update();
    }

    /**
     * Descobre de quem é o token (antes de existir usuário no contexto) e marca o uso.
     * Vazio se o token não existe ou foi revogado.
     */
    public Optional<Owner> authenticate(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 100) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        UPDATE webhook_token t SET last_used_at = now()
                          FROM app_user u
                         WHERE t.token_hash = :hash AND u.id = t.user_id
                        RETURNING u.id, u.email
                        """)
                .param("hash", hash(token))
                .query((rs, n) -> new Owner(rs.getObject("id", UUID.class), rs.getString("email")))
                .optional();
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
