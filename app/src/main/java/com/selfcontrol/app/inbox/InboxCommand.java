package com.selfcontrol.app.inbox;

import com.selfcontrol.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Dados de um lançamento pendente. amount é positivo; currency nula usa a moeda padrão do usuário;
 * categoryId nulo deixa sem categoria (ou, ao criar, tenta o mapeamento automático).
 */
public record InboxCommand(
        TransactionType type,
        BigDecimal amount,
        String currency,
        LocalDate occurredOn,
        UUID categoryId,
        String description) {
}
