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

        UUID first = accounts.findOrCreateForGoogle(subject, email, true, "Fulano", null).id();
        UUID second = accounts.findOrCreateForGoogle(subject, "outro@example.com", true, "Fulano", null).id();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("SELECT display_name FROM app_user WHERE id = :id").param("id", first)
                .query(String.class).single()).isEqualTo("Fulano");
    }

    @Test
    void linksToAnExistingPasswordAccountWithTheSameVerifiedEmail() {
        TestUser existing = newUser();

        UUID linked = accounts.findOrCreateForGoogle(UUID.randomUUID().toString(),
                existing.email().toUpperCase(), true, null, null).id();

        assertThat(linked).isEqualTo(existing.id());
    }

    @Test
    void refusesToLinkAnUnverifiedEmail() {
        TestUser existing = newUser();

        assertThatThrownBy(() -> accounts.findOrCreateForGoogle(UUID.randomUUID().toString(),
                existing.email(), false, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oidcUserCarriesTheAppUserId() {
        String subject = UUID.randomUUID().toString();
        OidcUser google = googleUser(subject, "oidc-" + subject + "@example.com", true);

        AppOidcUser user = oidcUsers.link(google);

        assertThat(user.getName()).isEqualTo("oidc-" + subject + "@example.com");
        assertThat(user.userId()).isEqualTo(accounts.findOrCreateForGoogle(subject, null, true, null, null).id());
    }

    @Test
    void googleLoginMarksTheEmailVerifiedAndUsesThePicture() {
        String subject = UUID.randomUUID().toString();
        UUID id = accounts.findOrCreateForGoogle(subject, "foto-" + subject + "@example.com", true, "Fulano",
                "https://lh3.googleusercontent.com/a/foto").id();

        assertThat(jdbc.sql("SELECT avatar_url FROM app_user WHERE id = :id AND email_verified_at IS NOT NULL")
                .param("id", id).query(String.class).single()).isEqualTo("https://lh3.googleusercontent.com/a/foto");
        // Foto que não é https é ignorada
        accounts.findOrCreateForGoogle(subject, null, true, null, "http://inseguro/foto");
        assertThat(jdbc.sql("SELECT avatar_url FROM app_user WHERE id = :id").param("id", id).query(String.class).single())
                .startsWith("https://");
    }

    @Test
    void disabledAccountCannotSignInWithGoogle() {
        String subject = UUID.randomUUID().toString();
        String email = "off-" + subject + "@example.com";
        UUID id = accounts.findOrCreateForGoogle(subject, email, true, "Fulano", null).id();
        jdbc.sql("UPDATE app_user SET active = false WHERE id = :id").param("id", id).update();

        assertThatThrownBy(() -> oidcUsers.link(googleUser(subject, email, true)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("desativada");
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
