package com.selfcontrol.app.auth;

import com.selfcontrol.app.shared.security.AppPrincipal;
import com.selfcontrol.app.shared.security.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.io.Serial;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Usuário logado pelo Google, já vinculado a app_user. O nome do principal é o e-mail. */
class AppOidcUser extends DefaultOidcUser implements AppPrincipal {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;

    AppOidcUser(UUID userId, Role role, OidcUser google) {
        super(authorities(google, role), google.getIdToken(), google.getUserInfo(), "email");
        this.userId = userId;
    }

    @Override
    public UUID userId() {
        return userId;
    }

    private static Collection<GrantedAuthority> authorities(OidcUser google, Role role) {
        Set<GrantedAuthority> all = new LinkedHashSet<>(google.getAuthorities());
        all.addAll(AuthorityUtils.createAuthorityList(role.authorities()));
        return all;
    }
}
