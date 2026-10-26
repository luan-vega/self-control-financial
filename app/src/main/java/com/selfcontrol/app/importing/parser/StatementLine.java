package com.selfcontrol.app.importing.parser;

import com.selfcontrol.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Uma linha do extrato já interpretada, ainda sem regra de negócio. amount é sempre positivo.
 * type é nulo quando o arquivo não diz (CSV só com valores positivos e sem coluna de tipo);
 * quem importa decide pela categoria. error preenchido quer dizer que a linha não serve.
 */
public record StatementLine(
        int lineNo,
        LocalDate date,
        String description,
        BigDecimal amount,
        TransactionType type,
        String category,
        String externalId,
        String error) {

    public static StatementLine invalid(int lineNo, String description, String error) {
        return new StatementLine(lineNo, null, description == null ? "" : description, null, null, null, null, error);
    }

    public boolean valid() {
        return error == null;
    }
}
