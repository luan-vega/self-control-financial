package com.selfcontrol.app.billing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StripeSignatureTest {

    private static final byte[] BODY = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "whsec_abc";

    @Test
    void acceptsAValidSignatureAmongSeveral() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        String valid = StripeSignature.sign(now.getEpochSecond(), BODY, SECRET);

        assertThat(StripeSignature.isValid(BODY, "t=1800000000,v1=" + valid, SECRET, now)).isTrue();
        // O Stripe manda mais de uma assinatura durante a troca de segredo
        assertThat(StripeSignature.isValid(BODY, "t=1800000000,v1=deadbeef,v0=x,v1=" + valid, SECRET, now)).isTrue();
        assertThat(StripeSignature.isValid(BODY, "t=1800000000, v1=" + valid, SECRET, now.plusSeconds(299))).isTrue();
    }

    @Test
    void rejectsTamperingReplayAndMissingParts() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        String valid = StripeSignature.sign(now.getEpochSecond(), BODY, SECRET);
        byte[] tampered = "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(StripeSignature.isValid(tampered, "t=1800000000,v1=" + valid, SECRET, now)).isFalse();
        assertThat(StripeSignature.isValid(BODY, "t=1800000000,v1=" + valid, SECRET, now.plusSeconds(301))).isFalse();
        assertThat(StripeSignature.isValid(BODY, "t=1800000000,v1=" + valid, "outro", now)).isFalse();
        assertThat(StripeSignature.isValid(BODY, "v1=" + valid, SECRET, now)).isFalse();
        assertThat(StripeSignature.isValid(BODY, "t=1800000000", SECRET, now)).isFalse();
        assertThat(StripeSignature.isValid(BODY, "lixo", SECRET, now)).isFalse();
        assertThat(StripeSignature.isValid(BODY, null, SECRET, now)).isFalse();
        assertThat(StripeSignature.isValid(BODY, "t=1800000000,v1=" + valid, "", now)).isFalse();
    }
}
