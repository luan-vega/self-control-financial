package com.selfcontrol.app.billing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Confere o header Stripe-Signature ({@code t=<timestamp>,v1=<hmac>}): HMAC-SHA256 de
 * {@code "<timestamp>.<corpo>"} com o segredo do endpoint. Recusa assinaturas mais velhas que a
 * tolerância para que um evento capturado não possa ser reenviado depois (replay).
 */
final class StripeSignature {

    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private StripeSignature() {
    }

    static boolean isValid(byte[] payload, String header, String secret, Instant now) {
        if (header == null || secret == null || secret.isBlank()) {
            return false;
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(timestamp), now).abs().compareTo(TOLERANCE) > 0) {
            return false;
        }
        byte[] expected = sign(timestamp, payload, secret).getBytes(StandardCharsets.US_ASCII);
        return signatures.stream().anyMatch(s -> MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.US_ASCII)));
    }

    /** Assinatura em hexa, como o Stripe gera (usado também pelos testes). */
    static String sign(long timestamp, byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
