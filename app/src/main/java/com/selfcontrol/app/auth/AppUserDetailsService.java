package com.selfcontrol.app.auth;

import com.selfcontrol.app.shared.security.AppUser;
import com.selfcontrol.app.shared.security.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Carrega usuários de login por e-mail e senha a partir da tabela app_user. Conta desativada não entra. */
@Service
class AppUserDetailsService implements UserDetailsService {

    private final JdbcClient jdbc;

    AppUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return jdbc.sql("""
                        SELECT id, email, password_hash, role, active
                          FROM app_user
                         WHERE lower(email) = lower(:email)
                           AND password_hash IS NOT NULL
                        """)
                .param("email", email)
                .query((rs, n) -> (UserDetails) new AppUser(rs.getObject("id", UUID.class),
                        rs.getString("email"), rs.getString("password_hash"), Role.valueOf(rs.getString("role")),
                        rs.getBoolean("active")))
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado"));
    }
}
