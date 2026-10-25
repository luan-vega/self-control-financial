package com.selfcontrol.domain.category;

import com.selfcontrol.domain.transaction.TransactionType;

import java.util.Objects;
import java.util.UUID;

public record Category(UUID id, UUID userId, String name, TransactionType type) {

    public Category {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A categoria precisa de um nome");
        }
        name = name.strip();
    }
}
