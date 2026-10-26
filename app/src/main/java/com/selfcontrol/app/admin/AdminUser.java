package com.selfcontrol.app.admin;

import com.selfcontrol.app.shared.security.Role;

import java.time.Instant;
import java.util.UUID;

/** Linha da lista de usuários do admin. */
public record AdminUser(UUID id, String email, String name, Role role, boolean active, boolean emailVerified,
                        String plan, Instant createdAt, Instant lastLoginAt) {
}
