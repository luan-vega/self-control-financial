package com.selfcontrol.app.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Login de apps nativos com Google na API: ID token assinado, emissor, audiência e conta já ligada. */
class GoogleIdTokensTests extends IntegrationTest {

    private static final String CLIENT_ID = "ios-app.apps.googleusercontent.com";

    private final KeyPair keys = rsa();

    @Test
    void acceptsAValidTokenForALinkedAccount() throws Exception {
        TestUser user = newUser();
        String subject = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO user_identity (provider, subject, user_id) VALUES ('google', :s, :u)")
                .param("s", subject).param("u", user.id()).update();
        GoogleIdTokens tokens = new GoogleIdTokens(decoder(keys), jdbc);

        assertThat(tokens.authenticate(token(subject, "https://accounts.google.com", CLIENT_ID, keys)))
                .hasValueSatisfying(c -> {
                    assertThat(c.userId()).isEqualTo(user.id());
                    assertThat(c.readOnly()).isFalse();
                });
        assertThat(tokens.authenticate(token(subject, "accounts.google.com", CLIENT_ID, keys))).isPresent();

        // Conta desativada, conta não ligada, outro app, outro emissor, assinatura de outra chave
        jdbc.sql("UPDATE app_user SET active = false WHERE id = :id").param("id", user.id()).update();
        assertThat(tokens.authenticate(token(subject, "https://accounts.google.com", CLIENT_ID, keys))).isEmpty();
        assertThat(tokens.authenticate(token("nao-ligado", "https://accounts.google.com", CLIENT_ID, keys))).isEmpty();
        assertThat(tokens.authenticate(token(subject, "https://accounts.google.com", "outro-app", keys))).isEmpty();
        assertThat(tokens.authenticate(token(subject, "https://evil.example", CLIENT_ID, keys))).isEmpty();
        assertThat(tokens.authenticate(token(subject, "https://accounts.google.com", CLIENT_ID, rsa()))).isEmpty();
        assertThat(tokens.authenticate("a.b.c")).isEmpty();
    }

    @Test
    void recognizesJwtShape() {
        assertThat(GoogleIdTokens.looksLikeJwt("a.b.c")).isTrue();
        assertThat(GoogleIdTokens.looksLikeJwt("scfk_abc")).isFalse();
    }

    private static NimbusJwtDecoder decoder(KeyPair keys) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),
                jwt -> GoogleIdTokens.validate(jwt, Set.of(CLIENT_ID))));
        return decoder;
    }

    private static String token(String subject, String issuer, String audience, KeyPair keys) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(keys.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
