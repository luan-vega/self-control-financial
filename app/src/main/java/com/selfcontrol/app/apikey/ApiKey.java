package com.selfcontrol.app.apikey;

import java.time.Instant;
import java.util.UUID;

/** O que as telas mostram de uma chave: nunca a chave em si, só o final para reconhecer. */
public record ApiKey(UUID id, String name, String hint, boolean readOnly, Instant createdAt, Instant lastUsedAt) {
}
