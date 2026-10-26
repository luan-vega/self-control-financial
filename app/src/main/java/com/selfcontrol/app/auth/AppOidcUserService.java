package com.selfcontrol.app.auth;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;


/** Depois do login no Google, vincula (ou cria) o usuário em app_user. Conta desativada não entra. */
@Component
public class AppOidcUserService extends OidcUserService {

    private final UserAccountService accounts;

    AppOidcUserService(UserAccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        return link(super.loadUser(request));
    }

    AppOidcUser link(OidcUser google) {
        try {
            UserAccountService.LinkedAccount account = accounts.findOrCreateForGoogle(google.getSubject(),
                    google.getEmail(), Boolean.TRUE.equals(google.getEmailVerified()), google.getFullName(),
                    google.getPicture());
            if (!account.active()) {
                throw new OAuth2AuthenticationException(new OAuth2Error("account_disabled"), "Conta desativada");
            }
            return new AppOidcUser(account.id(), account.role(), google);
        } catch (IllegalArgumentException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error("account_link_failed"), e.getMessage(), e);
        }
    }
}
