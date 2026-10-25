package com.selfcontrol.domain.category;

import com.selfcontrol.domain.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CategoryTest {

    private static final UUID USER = UUID.randomUUID();

    @Test
    void normalizesNameAndColor() {
        Category c = new Category(UUID.randomUUID(), USER, "  Mercado ", TransactionType.EXPENSE, " #10B981 ");

        assertThat(c.name()).isEqualTo("Mercado");
        assertThat(c.color()).isEqualTo("#10b981");
    }

    @Test
    void blankColorFallsBackToDefault() {
        assertThat(category("Extra", null).color()).isEqualTo(Category.DEFAULT_COLOR);
        assertThat(category("Extra", "").color()).isEqualTo(Category.DEFAULT_COLOR);
    }

    @Test
    void rejectsInvalidColor() {
        assertThatThrownBy(() -> category("Extra", "red")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> category("Extra", "#12345")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankOrLongNames() {
        assertThatThrownBy(() -> category(" ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> category("x".repeat(Category.MAX_NAME_LENGTH + 1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAcceptsTransactionsOfTheSameType() {
        Category expense = category("Mercado", null);

        assertThat(expense.accepts(TransactionType.EXPENSE)).isTrue();
        assertThat(expense.accepts(TransactionType.INCOME)).isFalse();
    }

    private static Category category(String name, String color) {
        return new Category(UUID.randomUUID(), USER, name, TransactionType.EXPENSE, color);
    }
}
