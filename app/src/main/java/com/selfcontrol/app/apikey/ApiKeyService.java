package com.selfcontrol.app.apikey;

import com.selfcontrol.app.shared.audit.AuditLog;
import com.selfcontrol.app.shared.error.ConflictException;
import com.selfcontrol.app.shared.error.InvalidInputException;
import com.selfcontrol.app.shared.error.NotFoundException;
import com.selfcontrol.app.shared.security.Role;
import com.selfcontrol.app.shared.security.Secrets;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Chaves da API pública (/api/v1). O banco guarda só o SHA-256, como no token do webhook. Cada
 * usuário pode ter várias (uma por app ou integração) e revogar uma sem afetar as outras.
 * Chave somente leitura só faz GET.
 */
@Service
@Transactional
public class ApiKeyService {

    public static final String PREFIX = "scfk_";
    static final int MAX_ACTIVE = 10;

    /** Uma chave recém-criada: o valor completo só existe aqui, uma vez. */
    public record Created(ApiKey key, String secret) {
    }

    /** Dono da chave, para autenticar a chamada como esse usuário. */
    public record Owner(UUID userId, String email, Role role, boolean readOnly, UUID keyId) {
    }

    private final JdbcClient jdbc;
    private final AuditLog audit;

    ApiKeyService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Created create(String name, boolean readOnly) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty() || value.length() > 60) {
            throw new InvalidInputException("name", "Dê um nome à chave (até 60 caracteres), ex.: \"App do iPhone\"");
        }
        int active = jdbc.sql("SELECT count(*) FROM api_key WHERE user_id = app_current_user_id() AND revoked_at IS NULL")
                .query(Integer.class)
                .single();
        if (active >= MAX_ACTIVE) {
            throw new ConflictException("Limite de " + MAX_ACTIVE + " chaves ativas. Revogue uma que não usa mais.");
        }
        String secret = Secrets.random(PREFIX);
        ApiKey key = jdbc.sql("""
                        INSERT INTO api_key (user_id, name, key_hash, key_hint, read_only)
                        SELECT id, :name, :hash, :hint, :readOnly FROM app_user WHERE id = app_current_user_id()
                        RETURNING id, name, key_hint, read_only, created_at, last_used_at
                        """)
                .param("name", value)
                .param("hash", Secrets.sha256(secret))
                .param("hint", secret.substring(secret.length() - 4))
                .param("readOnly", readOnly)
                .query((rs, n) -> map(rs))
                .single();
        audit.record("api_key.created", "api_key", key.id(), Map.of("name", value, "readOnly", readOnly));
        return new Created(key, secret);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list() {
        return jdbc.sql("""
                        SELECT id, name, key_hint, read_only, created_at, last_used_at FROM api_key
                         WHERE user_id = app_current_user_id() AND revoked_at IS NULL
                         ORDER BY created_at DESC
                        """)
                .query((rs, n) -> map(rs))
                .list();
    }

    public void revoke(UUID id) {
        int revoked = jdbc.sql("""
                        UPDATE api_key SET revoked_at = now()
                         WHERE id = :id AND user_id = app_current_user_id() AND revoked_at IS NULL
                        """)
                .param("id", id)
                .update();
        if (revoked == 0) {
            throw new NotFoundException("Chave não encontrada");
        }
        audit.record("api_key.revoked", "api_key", id, Map.of());
    }

    /**
     * Descobre de quem é a chave (antes de existir usuário no contexto). Vazio se a chave não existe,
     * foi revogada ou a conta está desativada. O último uso é gravado no máximo uma vez por minuto
     * para não escrever no banco a cada chamada.
     */
    public Optional<Owner> authenticate(String secret) {
        if (secret == null || !secret.startsWith(PREFIX) || secret.length() > 100) {
            return Optional.empty();
        }
        Optional<Owner> owner = jdbc.sql("""
                        SELECT k.id AS key_id, k.read_only, k.last_used_at, u.id, u.email, u.role
                          FROM api_key k
                          JOIN app_user u ON u.id = k.user_id
                         WHERE k.key_hash = :hash AND k.revoked_at IS NULL AND u.active
                        """)
                .param("hash", Secrets.sha256(secret))
                .query((rs, n) -> new Owner(rs.getObject("id", UUID.class), rs.getString("email"),
                        Role.valueOf(rs.getString("role")), rs.getBoolean("read_only"),
                        rs.getObject("key_id", UUID.class)))
                .optional();
        owner.ifPresent(o -> jdbc.sql("""
                        UPDATE api_key SET last_used_at = now()
                         WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')
                        """)
                .param("id", o.keyId())
                .update());
        return owner;
    }

    private static ApiKey map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiKey(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("key_hint"),
                rs.getBoolean("read_only"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("last_used_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
