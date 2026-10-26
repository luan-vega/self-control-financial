package com.selfcontrol.app.account;

import com.selfcontrol.app.shared.audit.AuditLog;
import com.selfcontrol.app.shared.error.InvalidInputException;
import com.selfcontrol.app.shared.security.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Perfil da conta logada: nome, moeda padrão e foto. O e-mail é o login e não muda por aqui. */
@Service
@Transactional
public class ProfileService {

    static final int MAX_AVATAR_BYTES = 512 * 1024;

    private final JdbcClient jdbc;
    private final AuditLog audit;

    ProfileService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Profile current() {
        return jdbc.sql("""
                        SELECT u.id, u.email, u.display_name, u.default_currency, u.role, u.email_verified_at,
                               u.password_hash IS NOT NULL AS has_password, a.user_id IS NOT NULL AS has_avatar,
                               u.avatar_url, u.created_at
                          FROM app_user u
                          LEFT JOIN user_avatar a ON a.user_id = u.id
                         WHERE u.id = app_current_user_id()
                        """)
                .query((rs, n) -> new Profile(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), Currency.getInstance(rs.getString("default_currency").strip()),
                        Role.valueOf(rs.getString("role")), rs.getTimestamp("email_verified_at") != null,
                        rs.getBoolean("has_password"), rs.getBoolean("has_avatar"), rs.getString("avatar_url"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Nenhum usuário conhecido na sessão"));
    }

    public Profile update(String name, String currency) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty() || value.length() > AccountService.MAX_NAME) {
            throw new InvalidInputException("name", "Informe seu nome (até " + AccountService.MAX_NAME + " caracteres)");
        }
        if (!SupportedCurrencies.isSupported(currency)) {
            throw new InvalidInputException("currency", "Escolha uma moeda da lista");
        }
        Profile before = current();
        jdbc.sql("""
                        UPDATE app_user SET display_name = :name, default_currency = :currency, updated_at = now()
                         WHERE id = app_current_user_id()
                        """)
                .param("name", value)
                .param("currency", currency)
                .update();
        Map<String, Object> changes = new LinkedHashMap<>();
        if (!before.name().equals(value)) {
            changes.put("name", value);
        }
        if (!before.currency().getCurrencyCode().equals(currency)) {
            changes.put("currency", currency);
        }
        if (!changes.isEmpty()) {
            audit.record("profile.updated", "user", before.id(), changes);
        }
        return current();
    }

    /** Aceita PNG, JPEG ou WebP de até 512 KB. O tipo vem dos bytes, não do nome do arquivo. */
    public void setAvatar(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new InvalidInputException("avatar", "Escolha uma imagem");
        }
        if (bytes.length > MAX_AVATAR_BYTES) {
            throw new InvalidInputException("avatar", "A foto pode ter até 512 KB");
        }
        String type = contentType(bytes)
                .orElseThrow(() -> new InvalidInputException("avatar", "Use uma imagem PNG, JPEG ou WebP"));
        jdbc.sql("""
                        INSERT INTO user_avatar (user_id, content_type, bytes) VALUES (app_current_user_id(), :type, :bytes)
                        ON CONFLICT (user_id) DO UPDATE
                           SET content_type = excluded.content_type, bytes = excluded.bytes, updated_at = now()
                        """)
                .param("type", type)
                .param("bytes", bytes)
                .update();
        audit.record("profile.avatar_changed", "user", current().id(), Map.of("contentType", type));
    }

    public void removeAvatar() {
        int removed = jdbc.sql("DELETE FROM user_avatar WHERE user_id = app_current_user_id()").update();
        if (removed > 0) {
            audit.record("profile.avatar_removed", "user", current().id(), Map.of());
        }
    }

    @Transactional(readOnly = true)
    public Optional<Avatar> avatar() {
        return jdbc.sql("SELECT content_type, bytes FROM user_avatar WHERE user_id = app_current_user_id()")
                .query((rs, n) -> new Avatar(rs.getString("content_type"), rs.getBytes("bytes")))
                .optional();
    }

    static Optional<String> contentType(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return Optional.of("image/png");
        }
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return Optional.of("image/jpeg");
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }
}
