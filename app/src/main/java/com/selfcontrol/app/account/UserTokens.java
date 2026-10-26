package com.selfcontrol.app.account;

import com.selfcontrol.app.shared.security.Secrets;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Tokens de uso único dos links enviados por e-mail. O banco guarda só o SHA-256, então quem lê o
 * banco não consegue usar um link pendente. Emitir um novo invalida os anteriores do mesmo tipo.
 */
@Component
class UserTokens {

    private final JdbcClient jdbc;

    UserTokens(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    String issue(UUID userId, TokenPurpose purpose) {
        jdbc.sql("DELETE FROM user_token WHERE user_id = :userId AND purpose = :purpose")
                .param("userId", userId)
                .param("purpose", purpose.name())
                .update();
        String token = Secrets.random("");
        jdbc.sql("""
                        INSERT INTO user_token (token_hash, user_id, purpose, expires_at)
                        VALUES (:hash, :userId, :purpose, now() + make_interval(secs => :ttl))
                        """)
                .param("hash", Secrets.sha256(token))
                .param("userId", userId)
                .param("purpose", purpose.name())
                .param("ttl", purpose.ttl.toSeconds())
                .update();
        return token;
    }

    /** Usa o token: devolve o dono se ele existe, é deste tipo, não venceu e não foi usado. */
    Optional<UUID> consume(String token, TokenPurpose purpose) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        UPDATE user_token SET used_at = now()
                         WHERE token_hash = :hash AND purpose = :purpose AND used_at IS NULL AND expires_at > now()
                        RETURNING user_id
                        """)
                .param("hash", Secrets.sha256(token))
                .param("purpose", purpose.name())
                .query(UUID.class)
                .optional();
    }

    /** Confere sem usar (para mostrar o formulário de nova senha só se o link ainda vale). */
    boolean isValid(String token, TokenPurpose purpose) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return false;
        }
        return jdbc.sql("""
                        SELECT count(*) FROM user_token
                         WHERE token_hash = :hash AND purpose = :purpose AND used_at IS NULL AND expires_at > now()
                        """)
                .param("hash", Secrets.sha256(token))
                .param("purpose", purpose.name())
                .query(Integer.class)
                .single() > 0;
    }
}
