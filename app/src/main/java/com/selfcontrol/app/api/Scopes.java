package com.selfcontrol.app.api;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Permissões da API pública: leitura (GET) e escrita (o resto). */
public final class Scopes {

    public static final GrantedAuthority READ = new SimpleGrantedAuthority("SCOPE_read");
    public static final GrantedAuthority WRITE = new SimpleGrantedAuthority("SCOPE_write");

    private Scopes() {
    }
}
