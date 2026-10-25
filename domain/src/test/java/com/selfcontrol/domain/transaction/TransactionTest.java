package com.selfcontrol.domain.transaction;

import com.selfcontrol.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionTest {

    @Test
    void stripsDescriptionAndTreatsNullAsEmpty() {
        assertThat(tx(LocalDate.of(2026, 10, 1), "  Mercado  ").description()).isEqualTo("Mercado");
        assertThat(tx(LocalDate.of(2026, 10, 1), null).description()).isEmpty();
    }

    @Test
    void rejectsDatesOutsideTheSaneRange() {
        assertThatThrownBy(() -> tx(LocalDate.of(26, 10, 1), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx(LocalDate.of(3000, 1, 1), "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLongDescriptions() {
        String longText = "a".repeat(Transaction.MAX_DESCRIPTION_LENGTH + 1);
        assertThatThrownBy(() -> tx(LocalDate.of(2026, 10, 1), longText)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Transaction tx(LocalDate date, String description) {
        return new Transaction(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TransactionType.EXPENSE,
                Money.of(100, "BRL"), date, description);
    }
}
