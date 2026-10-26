package com.selfcontrol.app.report;

import com.selfcontrol.app.IntegrationTest;
import com.selfcontrol.app.shared.security.RunAs;
import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.transaction.Balance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Agregações do relatório (jOOQ) contra o Postgres real. */
class ReportTests extends IntegrationTest {

    @Autowired
    ReportClient reports;

    @Test
    void sumsAndGroupsByCategoryAndMonth() throws Exception {
        TestUser user = newUser();
        UUID salary = category(user, "Salário", "INCOME", "#059669");
        UUID home = category(user, "Moradia", "EXPENSE", "#0ea5e9");
        UUID food = category(user, "Mercado", "EXPENSE", "#10b981");
        insert(user, salary, "INCOME", 500_000, "2026-09-05");
        insert(user, home, "EXPENSE", 150_000, "2026-09-06");
        insert(user, salary, "INCOME", 500_000, "2026-10-05");
        insert(user, home, "EXPENSE", 150_000, "2026-10-06");
        insert(user, food, "EXPENSE", 30_000, "2026-10-09");
        insert(user, food, "EXPENSE", 20_000, "2026-10-20");
        insert(user, food, "EXPENSE", 99_999, "2026-11-01"); // fora do período

        mvc.perform(get("/api/reports").param("from", "2026-09-01").param("to", "2026-10-31").with(user.login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.totals.incomeCents").value(1_000_000))
                .andExpect(jsonPath("$.totals.expensesCents").value(350_000))
                .andExpect(jsonPath("$.totals.netCents").value(650_000))
                .andExpect(jsonPath("$.expensesByCategory", hasSize(2)))
                .andExpect(jsonPath("$.expensesByCategory[0].name").value("Moradia"))
                .andExpect(jsonPath("$.expensesByCategory[0].totalCents").value(300_000))
                .andExpect(jsonPath("$.expensesByCategory[0].percent").value(86))
                .andExpect(jsonPath("$.expensesByCategory[1].name").value("Mercado"))
                .andExpect(jsonPath("$.expensesByCategory[1].totalCents").value(50_000))
                .andExpect(jsonPath("$.expensesByCategory[1].color").value("#10b981"))
                .andExpect(jsonPath("$.incomeByCategory[0].percent").value(100))
                .andExpect(jsonPath("$.months", hasSize(2)))
                .andExpect(jsonPath("$.months[0].month").value("2026-09"))
                .andExpect(jsonPath("$.months[0].totals.netCents").value(350_000))
                .andExpect(jsonPath("$.months[1].totals.expensesCents").value(200_000));
    }

    @Test
    void filtersByCategory() throws Exception {
        TestUser user = newUser();
        UUID home = category(user, "Moradia", "EXPENSE", null);
        UUID food = category(user, "Mercado", "EXPENSE", null);
        insert(user, home, "EXPENSE", 150_000, "2026-10-06");
        insert(user, food, "EXPENSE", 30_000, "2026-10-09");

        mvc.perform(get("/api/reports").param("from", "2026-10-01").param("to", "2026-10-31")
                        .param("categoryId", food.toString()).with(user.login()))
                .andExpect(jsonPath("$.totals.expensesCents").value(30_000))
                .andExpect(jsonPath("$.expensesByCategory", hasSize(1)));
    }

    @Test
    void onlyCountsTheUsersDefaultCurrencyAndOwnRows() throws Exception {
        TestUser user = newUser();
        TestUser other = newUser();
        UUID food = category(user, "Mercado", "EXPENSE", null);
        UUID otherFood = category(other, "Mercado", "EXPENSE", null);
        insert(user, food, "EXPENSE", 1_000, "2026-10-06");
        txInCurrency(user, food, 7_777, "USD", "2026-10-06");
        insert(other, otherFood, "EXPENSE", 50_000, "2026-10-06");

        mvc.perform(get("/api/reports").param("from", "2026-10-01").param("to", "2026-10-31").with(user.login()))
                .andExpect(jsonPath("$.totals.expensesCents").value(1_000));
    }

    @Test
    void allTimeBalanceAddsEverything() {
        TestUser user = newUser();
        UUID salary = category(user, "Salário", "INCOME", null);
        UUID food = category(user, "Mercado", "EXPENSE", null);
        insert(user, salary, "INCOME", 100_000, "2020-01-05");
        insert(user, food, "EXPENSE", 25_050, "2026-10-09");

        Balance balance = RunAs.user(user.id(), user.email(), reports::allTimeBalance);

        assertThat(balance.income()).isEqualTo(Money.of(100_000, "BRL"));
        assertThat(balance.expenses()).isEqualTo(Money.of(25_050, "BRL"));
        assertThat(balance.net()).isEqualTo(Money.of(74_950, "BRL"));
    }

    @Test
    void emptyReportDefaultsToTheCurrentMonth() throws Exception {
        mvc.perform(get("/api/reports").with(newUser().login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.netCents").value(0))
                .andExpect(jsonPath("$.months", hasSize(1)));
    }

    @Test
    void usesTheUsersDefaultCurrency() throws Exception {
        TestUser user = newUser("USD");
        UUID food = category(user, "Food", "EXPENSE", null);
        txInCurrency(user, food, 1_234, "USD", "2026-10-06");

        mvc.perform(get("/api/reports").param("from", "2026-10-01").param("to", "2026-10-31").with(user.login()))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.totals.expensesCents").value(1_234));
    }

    private UUID category(TestUser user, String name, String type, String color) {
        return as(user, () -> jdbc.sql("""
                        INSERT INTO category (user_id, name, type, color)
                        VALUES (:user, :name, :type, coalesce(:color, '#64748b')) RETURNING id
                        """)
                .param("user", user.id()).param("name", name).param("type", type).param("color", color)
                .query(UUID.class).single());
    }

    private void insert(TestUser user, UUID category, String type, long cents, String date) {
        as(user, () -> jdbc.sql("""
                        INSERT INTO financial_transaction (user_id, category_id, type, amount_cents, currency, occurred_on)
                        VALUES (:user, :category, :type, :cents, 'BRL', CAST(:date AS date))
                        """)
                .param("user", user.id()).param("category", category).param("type", type)
                .param("cents", cents).param("date", date)
                .update());
    }

    private void txInCurrency(TestUser user, UUID category, long cents, String currency, String date) {
        as(user, () -> jdbc.sql("""
                        INSERT INTO financial_transaction (user_id, category_id, type, amount_cents, currency, occurred_on)
                        VALUES (:user, :category, 'EXPENSE', :cents, :currency, CAST(:date AS date))
                        """)
                .param("user", user.id()).param("category", category).param("cents", cents)
                .param("currency", currency).param("date", date)
                .update());
    }
}
