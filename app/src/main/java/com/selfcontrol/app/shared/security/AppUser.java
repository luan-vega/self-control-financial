package com.selfcontrol.app.shared.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

import java.io.Serial;
import java.util.Collection;
import java.util.UUID;

/**
 * Usuário de login por e-mail e senha (e principal das chamadas por token ou chave de API).
 * Serializável porque fica na sessão (Spring Session JDBC).
 */
public class AppUser extends User implements AppPrincipal {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;

    public AppUser(UUID userId, String email, String passwordHash) {
        this(userId, email, passwordHash, Role.USER, true);
    }

    public AppUser(UUID userId, String email, String passwordHash, Role role, boolean active) {
        super(email, passwordHash == null ? "" : passwordHash, active, true, true, true,
                AuthorityUtils.createAuthorityList(role.authorities()));
        this.userId = userId;
    }

    /** Principal com permissões explícitas (chamadas por chave de API, que não têm senha). */
    public AppUser(UUID userId, String email, String passwordHash, Collection<? extends GrantedAuthority> authorities) {
        super(email, passwordHash == null ? "" : passwordHash, authorities);
        this.userId = userId;
    }

    @Override
    public UUID userId() {
        return userId;
    }
}
