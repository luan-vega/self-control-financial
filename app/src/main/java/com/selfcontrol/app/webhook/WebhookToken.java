package com.selfcontrol.app.webhook;

import java.time.Instant;

/** O que a tela mostra do token: nunca o token em si, só o final para reconhecer. */
public record WebhookToken(String hint, Instant createdAt, Instant lastUsedAt) {
}
