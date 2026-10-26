package com.selfcontrol.app.transaction;

import com.selfcontrol.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Dados para criar ou alterar um lançamento. amount é o valor decimal positivo ("12.34");
 * currency nula usa a moeda padrão do usuário.
 */
public record TransactionCommand(
        TransactionType type,
        BigDecimal amount,
        String currency,
        LocalDate occurredOn,
        UUID categoryId,
        String description) {
}
