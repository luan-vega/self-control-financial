package com.selfcontrol.app.shared.security;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

import java.io.Serial;
import java.util.UUID;

/** Usuário de login por e-mail e senha. Serializável porque fica na sessão (Spring Session JDBC). */
public class AppUser extends User implements AppPrincipal {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;

    public AppUser(UUID userId, String email, String passwordHash) {
        super(email, passwordHash == null ? "" : passwordHash, AuthorityUtils.createAuthorityList("ROLE_USER"));
        this.userId = userId;
    }

    @Override
    public UUID userId() {
        return userId;
    }
}
