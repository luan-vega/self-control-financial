package com.selfcontrol.domain.transaction;

import com.selfcontrol.domain.money.Money;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Lançamento financeiro. O valor é sempre positivo; o sinal vem do {@link TransactionType}.
 * A categoria pode ser nula só em dados antigos: a aplicação exige uma ao criar ou editar.
 */
public record Transaction(
        UUID id,
        UUID userId,
        UUID categoryId,
        TransactionType type,
        Money amount,
        LocalDate occurredOn,
        String description) {

    public static final int MAX_DESCRIPTION_LENGTH = 200;
    /** Limites de sanidade para datas digitadas (evita "0026-10-01" por engano). */
    public static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    public static final LocalDate MAX_DATE = LocalDate.of(2999, 12, 31);

    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(occurredOn, "occurredOn");
        if (amount.minorUnits() <= 0) {
            throw new IllegalArgumentException("O valor do lançamento deve ser maior que zero");
        }
        if (occurredOn.isBefore(MIN_DATE) || occurredOn.isAfter(MAX_DATE)) {
            throw new IllegalArgumentException("Data fora do intervalo aceito: " + occurredOn);
        }
        description = description == null ? "" : description.strip();
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "A descrição deve ter até " + MAX_DESCRIPTION_LENGTH + " caracteres");
        }
    }

    /** Valor com sinal: positivo para receita, negativo para despesa. */
    public Money signedAmount() {
        return type == TransactionType.INCOME ? amount : amount.negate();
    }
}
