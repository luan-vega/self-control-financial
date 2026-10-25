package com.selfcontrol.app.auth;

import com.selfcontrol.app.shared.security.AppPrincipal;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.io.Serial;
import java.util.UUID;

/** Usuário logado pelo Google, já vinculado a app_user. O nome do principal é o e-mail. */
class AppOidcUser extends DefaultOidcUser implements AppPrincipal {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;

    AppOidcUser(UUID userId, OidcUser google) {
        super(google.getAuthorities(), google.getIdToken(), google.getUserInfo(), "email");
        this.userId = userId;
    }

    @Override
    public UUID userId() {
        return userId;
    }
}
