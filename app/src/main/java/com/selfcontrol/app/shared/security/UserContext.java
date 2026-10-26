package com.selfcontrol.app.shared.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.UUID;

/**
 * Lê o usuário do contexto de RLS da transação atual. Assim o id usado nos inserts é
 * exatamente o mesmo que as políticas do Postgres usam para filtrar as leituras.
 */
@Component
public class UserContext {

    private final JdbcClient jdbc;

    UserContext(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public CurrentUser current() {
        return jdbc.sql("""
                        SELECT u.id, u.default_currency
                          FROM app_user u
                         WHERE u.id = app_current_user_id()
                        """)
                .query((rs, n) -> new CurrentUser(rs.getObject("id", UUID.class),
                        Currency.getInstance(rs.getString("default_currency").strip())))
                .optional()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException(
                        "Nenhum usuário conhecido na sessão"));
    }
}
