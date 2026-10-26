package com.selfcontrol.domain.importing;

import com.selfcontrol.domain.transaction.TransactionType;

import java.time.LocalDate;
import java.util.Objects;

/**
 * O que identifica um lançamento para a detecção de duplicatas: data + valor (com tipo e moeda)
 * + descrição normalizada. Dois lançamentos com a mesma impressão são considerados o mesmo.
 */
public record Fingerprint(LocalDate date, TransactionType type, long amountCents, String currency, String description) {

    public Fingerprint {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        currency = currency.strip();
        description = Descriptions.key(description);
    }
}
