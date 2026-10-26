package com.selfcontrol.app.transaction;

import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.transaction.Transaction;

/** Lançamento com a sua categoria já resolvida, para as telas e a API. category pode ser nula. */
public record TransactionEntry(Transaction transaction, Category category) {

    public String categoryName() {
        return category == null ? "Sem categoria" : category.name();
    }

    public String categoryColor() {
        return category == null ? Category.DEFAULT_COLOR : category.color();
    }
}
