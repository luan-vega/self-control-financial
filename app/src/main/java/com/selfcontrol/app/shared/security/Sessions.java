package com.selfcontrol.app.shared.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Encerra as sessões de uma conta (troca de senha, conta desativada). */
@Component
public class Sessions {

    private final JdbcClient jdbc;

    Sessions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** O Spring Session guarda o nome do principal, que aqui é sempre o e-mail. */
    public int endAll(String email) {
        return jdbc.sql("DELETE FROM spring_session WHERE lower(principal_name) = lower(:email)")
                .param("email", email)
                .update();
    }
}
