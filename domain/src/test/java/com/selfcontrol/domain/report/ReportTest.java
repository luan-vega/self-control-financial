package com.selfcontrol.domain.report;

import com.selfcontrol.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static com.selfcontrol.domain.transaction.TransactionType.EXPENSE;
import static com.selfcontrol.domain.transaction.TransactionType.INCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ReportTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final UUID HOME = UUID.randomUUID();
    private static final UUID FOOD = UUID.randomUUID();
    private static final UUID SALARY = UUID.randomUUID();

    private final Period october = Period.ofMonth(YearMonth.of(2026, 10));

    @Test
    void sumsTotalsAndSharesPerType() {
        Report report = Report.of(october, BRL, List.of(
                new CategorySum(FOOD, "Mercado", "#10b981", EXPENSE, brl(25_000)),
                new CategorySum(HOME, "Moradia", "#0ea5e9", EXPENSE, brl(75_000)),
                new CategorySum(SALARY, "Salário", "#059669", INCOME, brl(500_000))), List.of());

        assertThat(report.balance().income()).isEqualTo(brl(500_000));
        assertThat(report.balance().expenses()).isEqualTo(brl(100_000));
        assertThat(report.balance().net()).isEqualTo(brl(400_000));

        assertThat(report.expensesByCategory())
                .extracting(CategoryShare::name, CategoryShare::percent)
                .containsExactly(
                        tuple("Moradia", 75),
                        tuple("Mercado", 25));
        assertThat(report.incomeByCategory()).singleElement()
                .satisfies(s -> assertThat(s.percent()).isEqualTo(100));
    }

    @Test
    void tinySliceIsShownAsAtLeastOnePercent() {
        Report report = Report.of(october, BRL, List.of(
                new CategorySum(HOME, "Moradia", null, EXPENSE, brl(100_000)),
                new CategorySum(FOOD, "Café", null, EXPENSE, brl(1))), List.of());

        assertThat(report.expensesByCategory()).extracting(CategoryShare::percent).containsExactly(100, 1);
    }

    @Test
    void uncategorizedRowsGetAName() {
        Report report = Report.of(october, BRL, List.of(
                new CategorySum(null, null, null, EXPENSE, brl(500))), List.of());

        assertThat(report.expensesByCategory()).singleElement()
                .satisfies(s -> assertThat(s.name()).isEqualTo(CategorySum.UNCATEGORIZED));
    }

    @Test
    void emptyPeriodIsAllZeros() {
        Report report = Report.of(october, BRL, List.of(), List.of());

        assertThat(report.balance().net()).isEqualTo(Money.zero(BRL));
        assertThat(report.expensesByCategory()).isEmpty();
        assertThat(report.months()).singleElement()
                .satisfies(m -> assertThat(m.balance().net()).isEqualTo(Money.zero(BRL)));
        assertThat(Report.percent(brl(10), Money.zero(BRL))).isZero();
    }

    @Test
    void fillsEveryMonthOfThePeriodEvenWithoutData() {
        Period quarter = new Period(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 31));

        Report report = Report.of(quarter, BRL, List.of(), List.of(
                new MonthlySum(YearMonth.of(2026, 8), INCOME, brl(1_000)),
                new MonthlySum(YearMonth.of(2026, 10), EXPENSE, brl(300)),
                new MonthlySum(YearMonth.of(2026, 10), EXPENSE, brl(200))));

        assertThat(report.months()).extracting(MonthTotals::month)
                .containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(report.months().get(1).balance().net()).isEqualTo(Money.zero(BRL));
        assertThat(report.months().get(2).balance().expenses()).isEqualTo(brl(500));
        assertThat(report.largestMonthlyAmount()).isEqualTo(brl(1_000));
    }

    @Test
    void rejectsMonthsOutsideThePeriod() {
        assertThatThrownBy(() -> Report.of(october, BRL, List.of(),
                List.of(new MonthlySum(YearMonth.of(2026, 11), INCOME, brl(1)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Money brl(long cents) {
        return new Money(cents, BRL);
    }
}
