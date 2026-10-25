package com.selfcontrol.domain.report;

import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.transaction.TransactionType;

import java.util.Objects;
import java.util.UUID;

/**
 * Total de um tipo de lançamento em uma categoria. É a linha que sai da agregação no banco.
 * categoryId nulo representa lançamentos sem categoria.
 */
public record CategorySum(UUID categoryId, String name, String color, TransactionType type, Money total) {

    public static final String UNCATEGORIZED = "Sem categoria";

    public CategorySum {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(total, "total");
        if (categoryId == null) {
            name = UNCATEGORIZED;
        }
        Objects.requireNonNull(name, "name");
        color = color == null ? Category.DEFAULT_COLOR : color;
    }
}
