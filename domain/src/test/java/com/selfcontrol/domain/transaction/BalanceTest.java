package com.selfcontrol.domain.transaction;

import com.selfcontrol.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalanceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Currency BRL = Currency.getInstance("BRL");

    @Test
    void sumsIncomeAndExpensesSeparately() {
        var transactions = List.of(
                tx(TransactionType.INCOME, 500_000),
                tx(TransactionType.EXPENSE, 120_050),
                tx(TransactionType.EXPENSE, 4_950));

        Balance balance = Balance.of(transactions, BRL);

        assertThat(balance.income()).isEqualTo(Money.of(500_000, "BRL"));
        assertThat(balance.expenses()).isEqualTo(Money.of(125_000, "BRL"));
        assertThat(balance.net()).isEqualTo(Money.of(375_000, "BRL"));
    }

    @Test
    void emptyListIsZero() {
        assertThat(Balance.of(List.of(), BRL).net()).isEqualTo(Money.zero(BRL));
    }

    @Test
    void signedAmountIsNegativeForExpenses() {
        assertThat(tx(TransactionType.EXPENSE, 100).signedAmount()).isEqualTo(Money.of(-100, "BRL"));
        assertThat(tx(TransactionType.INCOME, 100).signedAmount()).isEqualTo(Money.of(100, "BRL"));
    }

    @Test
    void rejectsZeroOrNegativeAmounts() {
        assertThatThrownBy(() -> tx(TransactionType.EXPENSE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx(TransactionType.EXPENSE, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Transaction tx(TransactionType type, long cents) {
        return new Transaction(UUID.randomUUID(), USER, null, type,
                new Money(cents, BRL), LocalDate.of(2026, 10, 1), "teste");
    }
}
