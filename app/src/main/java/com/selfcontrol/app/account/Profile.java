package com.selfcontrol.app.account;

import com.selfcontrol.app.shared.security.Role;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** Perfil da conta logada. avatarUrl é a foto do Google, usada quando não há foto enviada. */
public record Profile(UUID id, String email, String name, Currency currency, Role role, boolean emailVerified,
                      boolean hasPassword, boolean hasAvatar, String avatarUrl, Instant createdAt) {

    public boolean admin() {
        return role == Role.ADMIN;
    }

    /** Iniciais para o avatar padrão ("Ana Souza" → "AS"). */
    public String initials() {
        String[] parts = name.strip().split("\\s+");
        String first = parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase();
    }
}
