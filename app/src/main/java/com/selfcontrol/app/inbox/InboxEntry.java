package com.selfcontrol.app.inbox;

import com.selfcontrol.app.importing.ImportSource;
import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.transaction.TransactionType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Lançamento pendente na caixa de entrada. category pode ser nula (o usuário escolhe antes de confirmar).
 * possibleDuplicate avisa que já existe um lançamento confirmado igual (mesma data, valor e descrição).
 */
public record InboxEntry(
        UUID id,
        ImportSource source,
        String fileName,
        TransactionType type,
        Money amount,
        LocalDate occurredOn,
        String description,
        Category category,
        boolean possibleDuplicate) {

    public boolean readyToConfirm() {
        return category != null && category.accepts(type);
    }

    public String categoryName() {
        return category == null ? "Sem categoria" : category.name();
    }

    public String categoryColor() {
        return category == null ? Category.DEFAULT_COLOR : category.color();
    }

    public String sourceLabel() {
        return switch (source) {
            case OFX, CSV -> fileName != null ? fileName : source.name();
            case WEBHOOK -> "Atalho do iPhone";
            case API -> "API";
        };
    }
}
