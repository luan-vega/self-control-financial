package com.selfcontrol.app.transaction;

import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.transaction.Transaction;
import com.selfcontrol.domain.transaction.TransactionType;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Linha da tabela financial_transaction. created_at é preenchido pelo banco. */
@Table("financial_transaction")
record TransactionRow(
        @Id UUID id,
        UUID userId,
        UUID categoryId,
        TransactionType type,
        long amountCents,
        String currency,
        LocalDate occurredOn,
        String description,
        @ReadOnlyProperty Instant createdAt,
        Instant updatedAt) {

    static TransactionRow from(Transaction t) {
        return new TransactionRow(t.id(), t.userId(), t.categoryId(), t.type(), t.amount().minorUnits(),
                t.amount().currency().getCurrencyCode(), t.occurredOn(), t.description(), null, Instant.now());
    }

    Transaction toDomain() {
        return new Transaction(id, userId, categoryId, type, Money.of(amountCents, currency.strip()),
                occurredOn, description);
    }
}
