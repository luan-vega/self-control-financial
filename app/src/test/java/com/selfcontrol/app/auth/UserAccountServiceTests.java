package com.selfcontrol.app.auth;

import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vínculo do login Google com app_user. */
class UserAccountServiceTests extends IntegrationTest {

    @Autowired
    UserAccountService accounts;

    @Autowired
    AppOidcUserService oidcUsers;

    @Test
    void createsAnAccountOnFirstLoginAndReusesItAfterwards() {
        String subject = UUID.randomUUID().toString();
        String email = "google-" + subject + "@example.com";

        UUID first = accounts.findOrCreateForGoogle(subject, email, true, "Fulano");
        UUID second = accounts.findOrCreateForGoogle(subject, "outro@example.com", true, "Fulano");

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("SELECT display_name FROM app_user WHERE id = :id").param("id", first)
                .query(String.class).single()).isEqualTo("Fulano");
    }

    @Test
    void linksToAnExistingPasswordAccountWithTheSameVerifiedEmail() {
        TestUser existing = newUser();

        UUID linked = accounts.findOrCreateForGoogle(UUID.randomUUID().toString(),
                existing.email().toUpperCase(), true, null);

        assertThat(linked).isEqualTo(existing.id());
    }

    @Test
    void refusesToLinkAnUnverifiedEmail() {
        TestUser existing = newUser();

        assertThatThrownBy(() -> accounts.findOrCreateForGoogle(UUID.randomUUID().toString(),
                existing.email(), false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oidcUserCarriesTheAppUserId() {
        String subject = UUID.randomUUID().toString();
        OidcUser google = googleUser(subject, "oidc-" + subject + "@example.com", true);

        AppOidcUser user = oidcUsers.link(google);

        assertThat(user.getName()).isEqualTo("oidc-" + subject + "@example.com");
        assertThat(user.userId()).isEqualTo(accounts.findOrCreateForGoogle(subject, null, true, null));
    }

    @Test
    void linkFailureBecomesAnOAuthError() {
        String subject = UUID.randomUUID().toString();
        OidcUser google = googleUser(subject, null, true);

        assertThatThrownBy(() -> oidcUsers.link(google)).isInstanceOf(OAuth2AuthenticationException.class);
    }

    private static OidcUser googleUser(String subject, String email, boolean verified) {
        var claims = new java.util.HashMap<String, Object>(Map.of("sub", subject, "email_verified", verified, "name", "Fulano"));
        if (email != null) {
            claims.put("email", email);
        }
        OidcIdToken token = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60), claims);
        return new DefaultOidcUser(List.of(), token, "sub");
    }
}
