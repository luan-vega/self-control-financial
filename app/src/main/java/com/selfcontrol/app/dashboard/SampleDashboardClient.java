package com.selfcontrol.app.dashboard;

import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.transaction.Balance;
import com.selfcontrol.domain.transaction.Transaction;
import com.selfcontrol.domain.transaction.TransactionType;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.selfcontrol.domain.transaction.TransactionType.EXPENSE;
import static com.selfcontrol.domain.transaction.TransactionType.INCOME;

/**
 * Stub da Fase 0: gera dados de exemplo em memória para a tela inicial.
 * Será substituído pela implementação com repositórios na Fase 1.
 */
@Component
class SampleDashboardClient implements DashboardClient {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final UUID SAMPLE_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private record Sample(String description, String category, TransactionType type, long cents, int day) {
    }

    private record Item(String category, Transaction transaction) {
    }

    private static final List<Sample> SAMPLES = List.of(
            new Sample("Salário", "Salário", INCOME, 850_000, 5),
            new Sample("Aluguel", "Moradia", EXPENSE, 220_000, 6),
            new Sample("Supermercado", "Mercado", EXPENSE, 64_390, 9),
            new Sample("Conta de luz", "Moradia", EXPENSE, 18_745, 12),
            new Sample("Uber", "Transporte", EXPENSE, 3_280, 14),
            new Sample("Freela site", "Extra", INCOME, 120_000, 18),
            new Sample("iFood", "Restaurantes", EXPENSE, 8_990, 21),
            new Sample("Academia", "Saúde", EXPENSE, 12_990, 22));

    @Override
    public DashboardView monthSummary(String username, YearMonth month) {
        List<Item> items = SAMPLES.stream()
                .map(s -> new Item(s.category(), new Transaction(UUID.randomUUID(), SAMPLE_USER, null, s.type(),
                        new Money(s.cents(), BRL), month.atDay(Math.min(s.day(), month.lengthOfMonth())),
                        s.description())))
                .toList();

        Balance balance = Balance.of(items.stream().map(Item::transaction).toList(), BRL);

        List<DashboardView.Entry> recent = items.stream()
                .sorted(Comparator.comparing((Item i) -> i.transaction().occurredOn()).reversed())
                .limit(6)
                .map(i -> new DashboardView.Entry(i.transaction().description(), i.category(),
                        i.transaction().type(), i.transaction().amount(), i.transaction().occurredOn()))
                .toList();

        return new DashboardView(month, balance, recent, topExpenses(items, balance));
    }

    private static List<DashboardView.CategoryTotal> topExpenses(List<Item> items, Balance balance) {
        Map<String, Money> totals = new LinkedHashMap<>();
        items.stream()
                .filter(i -> i.transaction().type() == EXPENSE)
                .forEach(i -> totals.merge(i.category(), i.transaction().amount(), Money::plus));

        long totalExpenses = Math.max(1, balance.expenses().minorUnits());
        return totals.entrySet().stream()
                .sorted(Map.Entry.<String, Money>comparingByValue().reversed())
                .limit(4)
                .map(e -> new DashboardView.CategoryTotal(e.getKey(), e.getValue(),
                        (int) Math.round(e.getValue().minorUnits() * 100.0 / totalExpenses)))
                .toList();
    }
}
