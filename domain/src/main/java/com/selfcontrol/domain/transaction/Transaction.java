package com.selfcontrol.domain.transaction;

import com.selfcontrol.domain.money.Money;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Lançamento financeiro. O valor é sempre positivo; o sinal vem do {@link TransactionType}.
 */
public record Transaction(
        UUID id,
        UUID userId,
        UUID categoryId,
        TransactionType type,
        Money amount,
        LocalDate occurredOn,
        String description) {

    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(occurredOn, "occurredOn");
        if (amount.minorUnits() <= 0) {
            throw new IllegalArgumentException("O valor do lançamento deve ser maior que zero");
        }
        description = description == null ? "" : description.strip();
    }

    /** Valor com sinal: positivo para receita, negativo para despesa. */
    public Money signedAmount() {
        return type == TransactionType.INCOME ? amount : amount.negate();
    }
}
