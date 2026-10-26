package com.selfcontrol.app.category;

import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.transaction.TransactionType;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/** Linha da tabela category (Spring Data JDBC). O domínio fica livre de anotações. */
@Table("category")
record CategoryRow(@Id UUID id, UUID userId, String name, TransactionType type, String color) {

    static CategoryRow from(Category c) {
        return new CategoryRow(c.id(), c.userId(), c.name(), c.type(), c.color());
    }

    Category toDomain() {
        return new Category(id, userId, name, type, color);
    }
}
