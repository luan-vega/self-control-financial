package com.selfcontrol.domain.report;

import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.transaction.Balance;
import com.selfcontrol.domain.transaction.TransactionType;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.Currency;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Relatório de um período em uma moeda: totais, quanto cada categoria pesou em receitas e
 * despesas, e a evolução mês a mês. Recebe somas já agregadas (do banco) e só faz as contas.
 */
public record Report(
        Period period,
        Currency currency,
        Balance balance,
        List<CategoryShare> expensesByCategory,
        List<CategoryShare> incomeByCategory,
        List<MonthTotals> months) {

    public Report {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(balance, "balance");
        expensesByCategory = List.copyOf(expensesByCategory);
        incomeByCategory = List.copyOf(incomeByCategory);
        months = List.copyOf(months);
    }

    public static Report of(Period period, Currency currency, List<CategorySum> byCategory, List<MonthlySum> byMonth) {
        Money zero = Money.zero(currency);
        Map<TransactionType, Money> totals = new EnumMap<>(TransactionType.class);
        for (CategorySum sum : byCategory) {
            totals.merge(sum.type(), sum.total(), Money::plus);
        }
        Balance balance = new Balance(
                totals.getOrDefault(TransactionType.INCOME, zero),
                totals.getOrDefault(TransactionType.EXPENSE, zero));

        return new Report(period, currency, balance,
                shares(byCategory, TransactionType.EXPENSE, balance.expenses()),
                shares(byCategory, TransactionType.INCOME, balance.income()),
                months(period, zero, byMonth));
    }

    private static List<CategoryShare> shares(List<CategorySum> sums, TransactionType type, Money total) {
        return sums.stream()
                .filter(s -> s.type() == type)
                .sorted(Comparator.comparing(CategorySum::total).reversed().thenComparing(CategorySum::name))
                .map(s -> new CategoryShare(s.categoryId(), s.name(), s.color(), s.total(), percent(s.total(), total)))
                .toList();
    }

    /** Arredonda para o inteiro mais próximo; uma fatia não nula nunca aparece como 0%. */
    static int percent(Money part, Money total) {
        if (total.minorUnits() == 0) {
            return 0;
        }
        int value = (int) Math.round(part.minorUnits() * 100.0 / total.minorUnits());
        return part.minorUnits() > 0 ? Math.max(1, value) : value;
    }

    private static List<MonthTotals> months(Period period, Money zero, List<MonthlySum> sums) {
        Map<YearMonth, Money[]> byMonth = new LinkedHashMap<>();
        for (YearMonth month : period.months()) {
            byMonth.put(month, new Money[]{zero, zero});
        }
        for (MonthlySum sum : sums) {
            Money[] pair = byMonth.get(sum.month());
            if (pair == null) {
                throw new IllegalArgumentException("Mês fora do período: " + sum.month());
            }
            int index = sum.type() == TransactionType.INCOME ? 0 : 1;
            pair[index] = pair[index].plus(sum.total());
        }
        return byMonth.entrySet().stream()
                .map(e -> new MonthTotals(e.getKey(), new Balance(e.getValue()[0], e.getValue()[1])))
                .toList();
    }

    /** Maior valor entre receitas e despesas dos meses, para escalar as barras do gráfico. */
    public Money largestMonthlyAmount() {
        Money max = Money.zero(currency);
        for (MonthTotals m : months) {
            if (m.balance().income().compareTo(max) > 0) {
                max = m.balance().income();
            }
            if (m.balance().expenses().compareTo(max) > 0) {
                max = m.balance().expenses();
            }
        }
        return max;
    }
}
