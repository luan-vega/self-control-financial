package com.selfcontrol.app.importing;

import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.transaction.TransactionType;

import java.time.LocalDate;
import java.util.UUID;

/** Linha do arquivo como aparece no preview e no log. amount, type e date são nulos em linhas com erro. */
public record ImportRow(
        UUID id,
        int lineNo,
        RowStatus status,
        TransactionType type,
        Money amount,
        LocalDate occurredOn,
        String description,
        Category category,
        String externalId,
        String message) {

    public boolean selectable() {
        return status != RowStatus.ERROR;
    }

    /** No preview, só as novas já vêm marcadas; duplicatas podem ser marcadas à mão. */
    public boolean selectedByDefault() {
        return status == RowStatus.NEW;
    }
}
