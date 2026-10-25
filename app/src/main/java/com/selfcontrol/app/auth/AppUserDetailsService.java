package com.selfcontrol.app.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Carrega usuários de login por e-mail e senha a partir da tabela app_user. */
@Service
class AppUserDetailsService implements UserDetailsService {

    private final JdbcClient jdbc;

    AppUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return jdbc.sql("""
                        SELECT email, password_hash
                          FROM app_user
                         WHERE lower(email) = lower(:email)
                           AND password_hash IS NOT NULL
                        """)
                .param("email", email)
                .query((rs, n) -> User.withUsername(rs.getString("email"))
                        .password(rs.getString("password_hash"))
                        .roles("USER")
                        .build())
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado"));
    }
}
