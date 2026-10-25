package com.selfcontrol.app.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Vincula identidades externas (Google) a um registro em app_user. Essas tabelas não têm RLS,
 * porque são lidas antes de existir um usuário no contexto.
 */
@Service
class UserAccountService {

    static final String GOOGLE = "google";

    private final JdbcClient jdbc;

    UserAccountService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devolve o usuário ligado a esta conta Google. Na primeira vez, liga a uma conta existente
     * com o mesmo e-mail (só se o Google confirmou o e-mail) ou cria uma conta nova.
     */
    @Transactional
    public UUID findOrCreateForGoogle(String subject, String email, boolean emailVerified, String displayName) {
        Optional<UUID> linked = jdbc.sql("SELECT user_id FROM user_identity WHERE provider = :p AND subject = :s")
                .param("p", GOOGLE)
                .param("s", subject)
                .query(UUID.class)
                .optional();
        if (linked.isPresent()) {
            return linked.get();
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
        UUID userId = existing.orElseGet(() -> jdbc.sql("""
                        INSERT INTO app_user (email, display_name) VALUES (:email, :name) RETURNING id
                        """)
                .param("email", email)
                .param("name", displayName == null || displayName.isBlank() ? email : displayName)
                .query(UUID.class)
                .single());

        jdbc.sql("INSERT INTO user_identity (provider, subject, user_id) VALUES (:p, :s, :u)")
                .param("p", GOOGLE)
                .param("s", subject)
                .param("u", userId)
                .update();
        return userId;
    }
}
