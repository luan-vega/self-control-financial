package com.selfcontrol.app;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Telas Thymeleaf + HTMX: página inteira, fragmentos e formulários. */
class PageTests extends IntegrationTest {

    @Test
    void dashboardShowsRealNumbersAndChart() throws Exception {
        TestUser user = newUser();
        UUID salary = category(user, "Salário", "INCOME");
        UUID home = category(user, "Moradia", "EXPENSE");
        insert(user, salary, "INCOME", 850_000, "2026-09-05", "Salário setembro");
        insert(user, home, "EXPENSE", 220_000, "2026-10-06", "Aluguel");

        mvc.perform(get("/").param("month", "2026-10").with(user.login()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Saldo total"),
                        containsString("6.300,00"),   // 8.500 - 2.200 acumulado
                        containsString("2.200,00"),
                        containsString("Aluguel"),
                        containsString("stroke-dasharray=\"100.000 0.000\""),
                        not(containsString("Comece criando suas categorias")))));
    }

    @Test
    void newUserIsInvitedToCreateCategories() throws Exception {
        mvc.perform(get("/").with(newUser().login()))
                .andExpect(content().string(allOf(
                        containsString("Comece criando suas categorias"),
                        containsString("Sem despesas neste mês"))));
    }

    @Test
    void statementListsTheMonthWithTotals() throws Exception {
        TestUser user = newUser();
        UUID food = category(user, "Mercado", "EXPENSE");
        UUID home = category(user, "Moradia", "EXPENSE");
        insert(user, food, "EXPENSE", 64_390, "2026-10-09", "Supermercado");
        insert(user, home, "EXPENSE", 18_745, "2026-10-12", "Conta de luz");
        insert(user, food, "EXPENSE", 1_000, "2026-09-30", "Mês passado");

        mvc.perform(get("/transactions").param("month", "2026-10").with(user.login()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Extrato"),
                        containsString("Supermercado"),
                        containsString("Conta de luz"),
                        containsString("831,35"),
                        containsString("Segunda-feira, 12 de outubro"),
                        not(containsString("Mês passado")))));

        mvc.perform(get("/transactions").param("month", "2026-10").param("categoryId", food.toString())
                        .header("HX-Request", "true").with(user.login()))
                .andExpect(content().string(allOf(
                        containsString("Supermercado"),
                        not(containsString("Conta de luz")),
                        not(containsString("<html")))));

        mvc.perform(get("/transactions").param("month", "2026-08").with(user.login()))
                .andExpect(content().string(containsString("Nenhum lançamento neste mês")));
    }

    @Test
    void createTransactionThroughTheModal() throws Exception {
        TestUser user = newUser();
        UUID food = category(user, "Mercado", "EXPENSE");
        category(user, "Salário", "INCOME");

        mvc.perform(get("/transactions/new").with(user.login()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Novo lançamento"),
                        containsString("Mercado"),
                        not(containsString("Salário")))));

        mvc.perform(get("/transactions/category-options").param("type", "INCOME").with(user.login()))
                .andExpect(content().string(allOf(containsString("Salário"), not(containsString("Mercado")))));

        mvc.perform(post("/transactions").with(user.login()).with(csrf())
                        .param("type", "EXPENSE")
                        .param("amount", "1.234,56")
                        .param("occurredOn", "2026-10-15")
                        .param("categoryId", food.toString())
                        .param("description", "Feira"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Refresh", "true"));

        long cents = as(user, () -> jdbc.sql("SELECT amount_cents FROM financial_transaction WHERE description = 'Feira'")
                .query(Long.class).single());
        assertThat(cents).isEqualTo(123_456);
    }

    @Test
    void invalidFormComesBackWithMessages() throws Exception {
        TestUser user = newUser();
        UUID salary = category(user, "Salário", "INCOME");

        mvc.perform(post("/transactions").with(user.login()).with(csrf())
                        .param("type", "EXPENSE")
                        .param("amount", "abc")
                        .param("occurredOn", "2026-10-15"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("HX-Refresh"))
                .andExpect(content().string(allOf(
                        containsString("Valor inválido"),
                        containsString("Escolha uma categoria"))));

        mvc.perform(post("/transactions").with(user.login()).with(csrf())
                        .param("type", "EXPENSE")
                        .param("amount", "0")
                        .param("occurredOn", "2026-10-15")
                        .param("categoryId", salary.toString()))
                .andExpect(content().string(containsString("O valor deve ser maior que zero")));

        mvc.perform(post("/transactions").with(user.login()).with(csrf())
                        .param("type", "EXPENSE")
                        .param("amount", "10")
                        .param("occurredOn", "2026-10-15")
                        .param("categoryId", salary.toString()))
                .andExpect(content().string(containsString("é de receitas")));
    }

    @Test
    void editAndDeleteInline() throws Exception {
        TestUser user = newUser();
        UUID food = category(user, "Mercado", "EXPENSE");
        UUID id = insert(user, food, "EXPENSE", 4_990, "2026-10-01", "Padaria");

        mvc.perform(get("/transactions/{id}/edit", id).with(user.login()))
                .andExpect(content().string(allOf(
                        containsString("Editar lançamento"),
                        containsString("value=\"49,90\""),
                        containsString("Padaria"),
                        containsString("Excluir lançamento"))));

        mvc.perform(post("/transactions/{id}", id).with(user.login()).with(csrf())
                        .param("type", "EXPENSE").param("amount", "52,00").param("occurredOn", "2026-10-02")
                        .param("categoryId", food.toString()).param("description", "Padaria nova"))
                .andExpect(header().string("HX-Refresh", "true"));

        mvc.perform(delete("/transactions/{id}", id).with(user.login()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "transactions-changed"));

        int left = as(user, () -> jdbc.sql("SELECT count(*) FROM financial_transaction").query(Integer.class).single());
        assertThat(left).isZero();
    }

    @Test
    void cannotOpenAnotherUsersTransaction() throws Exception {
        TestUser alice = newUser();
        UUID food = category(alice, "Mercado", "EXPENSE");
        UUID id = insert(alice, food, "EXPENSE", 100, "2026-10-01", "Segredo");

        mvc.perform(get("/transactions/{id}/edit", id).with(newUser().login()))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoriesPageFlow() throws Exception {
        TestUser user = newUser();

        mvc.perform(get("/categories").with(user.login()))
                .andExpect(content().string(containsString("Criar categorias sugeridas")));

        mvc.perform(post("/categories/defaults").with(user.login()).with(csrf()))
                .andExpect(content().string(allOf(containsString("Moradia"), containsString("Salário"),
                        not(containsString("Criar categorias sugeridas")))));

        mvc.perform(post("/categories").with(user.login()).with(csrf())
                        .param("name", "Pets").param("type", "EXPENSE").param("color", "#A855F7"))
                .andExpect(content().string(allOf(containsString("Pets"), containsString("#a855f7"))));

        mvc.perform(post("/categories").with(user.login()).with(csrf())
                        .param("name", "pets").param("type", "EXPENSE").param("color", "#a855f7"))
                .andExpect(content().string(containsString("Já existe uma categoria")));

        mvc.perform(post("/categories").with(user.login()).with(csrf())
                        .param("name", "").param("type", "EXPENSE").param("color", "#a855f7"))
                .andExpect(content().string(containsString("Informe o nome")));

        UUID pets = as(user, () -> jdbc.sql("SELECT id FROM category WHERE name = 'Pets'").query(UUID.class).single());

        mvc.perform(get("/categories/{id}/edit", pets).with(user.login()))
                .andExpect(content().string(allOf(containsString("Salvar"), containsString("Cancelar"))));

        mvc.perform(post("/categories/{id}", pets).with(user.login()).with(csrf())
                        .param("name", "Pet shop").param("type", "EXPENSE").param("color", "#a855f7"))
                .andExpect(content().string(containsString("Pet shop")));

        mvc.perform(post("/categories/{id}", pets).with(user.login()).with(csrf())
                        .param("name", "Moradia").param("type", "EXPENSE").param("color", "#a855f7"))
                .andExpect(content().string(containsString("Já existe uma categoria")));

        insert(user, pets, "EXPENSE", 100, "2026-10-01", "Ração");
        mvc.perform(delete("/categories/{id}", pets).with(user.login()).with(csrf()))
                .andExpect(content().string(containsString("tem lançamentos e não pode ser excluída")));

        UUID leisure = as(user, () -> jdbc.sql("SELECT id FROM category WHERE name = 'Lazer'").query(UUID.class).single());
        mvc.perform(delete("/categories/{id}", leisure).with(user.login()).with(csrf()))
                .andExpect(content().string(not(containsString("Lazer"))));
    }

    @Test
    void reportPageWithFilters() throws Exception {
        TestUser user = newUser();
        UUID salary = category(user, "Salário", "INCOME");
        UUID home = category(user, "Moradia", "EXPENSE");
        insert(user, salary, "INCOME", 500_000, "2026-09-05", "Salário");
        insert(user, home, "EXPENSE", 150_000, "2026-10-06", "Aluguel");

        mvc.perform(get("/reports").param("from", "2026-09-01").param("to", "2026-10-31").with(user.login()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Relatórios"),
                        containsString("01/09/2026 a 31/10/2026"),
                        containsString("Mês a mês"),
                        containsString("Set/26"),
                        containsString("3.500,00"),
                        containsString("Lançamentos (2)"))));

        mvc.perform(get("/reports").param("from", "2026-10-01").param("to", "2026-10-31")
                        .param("categoryId", home.toString()).header("HX-Request", "true").with(user.login()))
                .andExpect(content().string(allOf(
                        containsString("Lançamentos (1)"),
                        not(containsString("Mês a mês")),
                        not(containsString("<html")))));

        mvc.perform(get("/reports").param("from", "2026-10-31").param("to", "2026-10-01").with(user.login()))
                .andExpect(content().string(containsString("Mostrando o mês atual")));

        mvc.perform(get("/reports").with(user.login())).andExpect(status().isOk());
    }

    private UUID category(TestUser user, String name, String type) {
        return as(user, () -> jdbc.sql("INSERT INTO category (name, type) VALUES (:name, :type) RETURNING id")
                .param("name", name).param("type", type)
                .query(UUID.class).single());
    }

    private UUID insert(TestUser user, UUID category, String type, long cents, String date, String description) {
        return as(user, () -> jdbc.sql("""
                        INSERT INTO financial_transaction (category_id, type, amount_cents, currency, occurred_on, description)
                        VALUES (:category, :type, :cents, 'BRL', CAST(:date AS date), :description) RETURNING id
                        """)
                .param("category", category).param("type", type).param("cents", cents)
                .param("date", date).param("description", description)
                .query(UUID.class).single());
    }
}
