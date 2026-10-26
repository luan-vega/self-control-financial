package com.selfcontrol.app.auth;

import com.selfcontrol.app.account.AdminEmails;
import com.selfcontrol.app.shared.audit.AuditLog;
import com.selfcontrol.app.shared.security.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Vincula identidades externas (Google) a um registro em app_user. Essas tabelas não têm RLS,
 * porque são lidas antes de existir um usuário no contexto.
 */
@Service
class UserAccountService {

    static final String GOOGLE = "google";

    /** Conta ligada ao login externo, com o que o login precisa saber. */
    record LinkedAccount(UUID id, Role role, boolean active) {
    }

    private final JdbcClient jdbc;
    private final AdminEmails admins;
    private final AuditLog audit;

    UserAccountService(JdbcClient jdbc, AdminEmails admins, AuditLog audit) {
        this.jdbc = jdbc;
        this.admins = admins;
        this.audit = audit;
    }

    /**
     * Devolve o usuário ligado a esta conta Google. Na primeira vez, liga a uma conta existente
     * com o mesmo e-mail (só se o Google confirmou o e-mail) ou cria uma conta nova. A conta nova
     * ainda precisa aceitar os termos na primeira tela (ver TermsInterceptor). A foto do Google vira
     * a foto do perfil enquanto o usuário não enviar outra.
     */
    @Transactional
    public LinkedAccount findOrCreateForGoogle(String subject, String email, boolean emailVerified, String displayName,
                                               String picture) {
        Optional<UUID> linked = jdbc.sql("SELECT user_id FROM user_identity WHERE provider = :p AND subject = :s")
                .param("p", GOOGLE)
                .param("s", subject)
                .query(UUID.class)
                .optional();
        if (linked.isPresent()) {
            return refresh(linked.get(), emailVerified, picture);
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("O Google não informou o e-mail da conta");
        }

        Optional<UUID> existing = jdbc.sql("SELECT id FROM app_user WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query(UUID.class)
                .optional();
        if (existing.isPresent() && !emailVerified) {
            throw new IllegalArgumentException("E-mail não verificado pelo Google; não dá para vincular à conta existente");
        }
        UUID userId = existing.orElseGet(() -> {
            UUID created = jdbc.sql("""
                            INSERT INTO app_user (email, display_name, role) VALUES (:email, :name, :role) RETURNING id
                            """)
                    .param("email", email)
                    .param("name", displayName == null || displayName.isBlank() ? email : displayName.strip())
                    .param("role", admins.isAdmin(email) ? "ADMIN" : "USER")
                    .query(UUID.class)
                    .single();
            audit.record(created, created, "account.registered", "user", created, Map.of("provider", GOOGLE));
            return created;
        });

        jdbc.sql("INSERT INTO user_identity (provider, subject, user_id) VALUES (:p, :s, :u)")
                .param("p", GOOGLE)
                .param("s", subject)
                .param("u", userId)
                .update();
        return refresh(userId, emailVerified, picture);
    }

    private LinkedAccount refresh(UUID userId, boolean emailVerified, String picture) {
        return jdbc.sql("""
                        UPDATE app_user
                           SET email_verified_at = CASE WHEN :verified THEN coalesce(email_verified_at, now())
                                                        ELSE email_verified_at END,
                               avatar_url = CASE WHEN :picture LIKE 'https://%' THEN :picture ELSE avatar_url END
                         WHERE id = :id
                        RETURNING id, role, active
                        """)
                .param("verified", emailVerified)
                .param("picture", picture == null ? "" : picture)
                .param("id", userId)
                .query((rs, n) -> new LinkedAccount(rs.getObject("id", UUID.class), Role.valueOf(rs.getString("role")),
                        rs.getBoolean("active")))
                .single();
    }
}
