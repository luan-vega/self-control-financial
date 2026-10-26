package com.selfcontrol.app.inbox;

import com.jayway.jsonpath.JsonPath;
import com.selfcontrol.app.IntegrationTest;
import com.selfcontrol.app.importing.ImportSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static com.selfcontrol.domain.transaction.TransactionType.EXPENSE;
import static com.selfcontrol.domain.transaction.TransactionType.INCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Caixa de entrada: revisar, editar, confirmar e descartar em lote, sempre isolada por usuário. */
class InboxTests extends IntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    @Autowired
    private InboxClient inbox;

    @Test
    void bulkConfirmCreatesTransactionsAndKeepsItemsWithoutCategory() throws Exception {
        TestUser user = newUser();
        UUID food = category(user, "Mercado", "EXPENSE");
        UUID withCategory = add(user, EXPENSE, "643.90", "Supermercado", food);
        UUID withoutCategory = add(user, EXPENSE, "10", "Desconhecido", null);

        bulk(user, "/inbox/confirm", withCategory, withoutCategory)
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("1 lançamento confirmado."),
                        containsString("1 continua aqui porque está sem categoria"))));

        assertThat(count(user, "SELECT count(*) FROM financial_transaction WHERE amount_cents = 64390 AND description = 'Supermercado'"))
                .isEqualTo(1);
        assertThat(as(user, inbox::pending)).extracting(InboxEntry::id).containsExactly(withoutCategory);
        assertThat(as(user, inbox::pendingCount)).isEqualTo(1);
        // O dashboard mostra o contador
        mvc.perform(get("/").with(user.login()))
                .andExpect(content().string(containsString("Caixa de entrada: 1 pendentes")));
    }

    @Test
    void discardHidesButRemembersForDuplicateDetection() throws Exception {
        TestUser user = newUser();
        UUID a = add(user, EXPENSE, "5", "Tarifa", null);
        UUID b = add(user, EXPENSE, "6", "Outra", null);

        bulk(user, "/inbox/discard", a, b).andExpect(content().string(containsString("2 lançamentos descartados.")));
        bulk(user, "/inbox/discard").andExpect(content().string(containsString("Selecione os lançamentos")));
        bulk(user, "/inbox/confirm").andExpect(content().string(containsString("Selecione os lançamentos")));

        assertThat(as(user, inbox::pending)).isEmpty();
        assertThat(count(user, "SELECT count(*) FROM inbox_item WHERE status = 'DISCARDED'")).isEqualTo(2);
        mvc.perform(get("/inbox").with(user.login())).andExpect(content().string(containsString("Nada pendente")));
    }

    @Test
    void quickCategoryChangeAndFullEdit() throws Exception {
        TestUser user = newUser();
        UUID food = category(user, "Mercado", "EXPENSE");
        UUID salary = category(user, "Salário", "INCOME");
        UUID id = add(user, EXPENSE, "10", "Padaria", null);

        mvc.perform(post("/inbox/{id}/category", id).param("categoryId", food.toString()).with(user.login()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("selected")));
        assertThat(as(user, () -> inbox.get(id)).category().id()).isEqualTo(food);

        // Categoria de receita num lançamento de despesa: erro na própria linha
        mvc.perform(post("/inbox/{id}/category", id).param("categoryId", salary.toString()).with(user.login()).with(csrf()))
                .andExpect(content().string(containsString("é de receitas")));

        mvc.perform(get("/inbox/{id}/edit", id).with(user.login()))
                .andExpect(content().string(allOf(containsString("Salvar"), containsString("10,00"))));
        mvc.perform(post("/inbox/{id}", id).with(user.login()).with(csrf())
                        .param("type", "INCOME").param("amount", "1.234,56").param("occurredOn", "2026-10-11")
                        .param("categoryId", salary.toString()).param("description", "Reembolso"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Reembolso"), containsString("1.234,56"))));
        InboxEntry edited = as(user, () -> inbox.get(id));
        assertThat(edited.type()).isEqualTo(INCOME);
        assertThat(edited.amount().minorUnits()).isEqualTo(123456);
        assertThat(edited.occurredOn()).isEqualTo(LocalDate.of(2026, 10, 11));

        // Erros voltam no formulário
        mvc.perform(post("/inbox/{id}", id).with(user.login()).with(csrf())
                        .param("type", "EXPENSE").param("amount", "abc").param("occurredOn", "2026-10-11"))
                .andExpect(content().string(containsString("Valor inválido")));
        mvc.perform(post("/inbox/{id}", id).with(user.login()).with(csrf())
                        .param("type", "EXPENSE").param("amount", "10").param("occurredOn", "2026-10-11")
                        .param("categoryId", salary.toString()))
                .andExpect(content().string(containsString("é de receitas")));
        mvc.perform(get("/inbox/{id}", id).with(user.login())).andExpect(content().string(containsString("Reembolso")));
    }

    @Test
    void flagsPossibleDuplicatesOfConfirmedTransactions() throws Exception {
        TestUser user = newUser();
        UUID food = category(user, "Mercado", "EXPENSE");
        UUID first = add(user, EXPENSE, "20", "Feira", food);
        bulk(user, "/inbox/confirm", first);
        add(user, EXPENSE, "20", "FEIRA", null);

        mvc.perform(get("/inbox").with(user.login()))
                .andExpect(content().string(containsString("Possível duplicata")));
    }

    @Test
    void validatesNewItems() {
        TestUser user = newUser();
        UUID salary = as(user, () -> UUID.fromString(jdbc.sql(
                "INSERT INTO category (name, type) VALUES ('Salário', 'INCOME') RETURNING id::text").query(String.class).single()));

        assertThatThrownBy(() -> as(user, () -> inbox.add(command(EXPENSE, "0", null), ImportSource.WEBHOOK)))
                .hasMessageContaining("maior que zero");
        assertThatThrownBy(() -> as(user, () -> inbox.add(command(EXPENSE, "1.001", null), ImportSource.WEBHOOK)))
                .hasMessageContaining("casas decimais");
        assertThatThrownBy(() -> as(user, () -> inbox.add(new InboxCommand(EXPENSE, BigDecimal.ONE, null,
                LocalDate.of(1800, 1, 1), null, ""), ImportSource.WEBHOOK)))
                .hasMessageContaining("Data inválida");
        assertThatThrownBy(() -> as(user, () -> inbox.add(command(EXPENSE, "1", salary), ImportSource.WEBHOOK)))
                .hasMessageContaining("é de receitas");
        assertThatThrownBy(() -> as(user, () -> inbox.add(command(EXPENSE, "1", UUID.randomUUID()), ImportSource.WEBHOOK)))
                .hasMessageContaining("Categoria não encontrada");
        assertThatThrownBy(() -> as(user, () -> inbox.add(new InboxCommand(EXPENSE, BigDecimal.ONE, "XYZ", DAY, null, ""),
                ImportSource.WEBHOOK)))
                .hasMessageContaining("Moeda desconhecida");
        assertThatThrownBy(() -> as(user, () -> inbox.add(new InboxCommand(EXPENSE, BigDecimal.ONE, null, DAY, null,
                "x".repeat(201)), ImportSource.WEBHOOK)))
                .hasMessageContaining("até 200");
        // Sem tipo, vem da categoria
        assertThat(as(user, () -> inbox.add(command(null, "1", salary), ImportSource.WEBHOOK)).type()).isEqualTo(INCOME);
    }

    @Test
    void inboxIsIsolatedPerUser() throws Exception {
        TestUser alice = newUser();
        TestUser bob = newUser();
        UUID aliceFood = category(alice, "Mercado", "EXPENSE");
        UUID item = add(alice, EXPENSE, "99.90", "Segredo da Alice", aliceFood);

        mvc.perform(get("/inbox").with(bob.login()))
                .andExpect(content().string(allOf(containsString("Nada pendente"), not(containsString("Segredo da Alice")))));
        mvc.perform(get("/inbox/{id}/edit", item).with(bob.login())).andExpect(status().isNotFound());
        bulk(bob, "/inbox/confirm", item).andExpect(content().string(containsString("Selecione os lançamentos")));
        bulk(bob, "/inbox/discard", item).andExpect(content().string(containsString("Selecione os lançamentos")));
        assertThat(count(bob, "SELECT count(*) FROM inbox_item")).isZero();
        assertThat(count(bob, "SELECT count(*) FROM financial_transaction")).isZero();

        // Bob não consegue gravar um item com a categoria da Alice (FK composta + RLS)
        assertThatThrownBy(() -> as(bob, () -> jdbc.sql("""
                        INSERT INTO inbox_item (source, type, amount_cents, currency, occurred_on, category_id)
                        VALUES ('WEBHOOK', 'EXPENSE', 100, 'BRL', current_date, :category)
                        """).param("category", aliceFood).update()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> as(bob, () -> jdbc.sql("""
                        INSERT INTO inbox_item (user_id, source, type, amount_cents, currency, occurred_on)
                        VALUES (:user, 'WEBHOOK', 'EXPENSE', 100, 'BRL', current_date)
                        """).param("user", alice.id()).update()))
                .rootCause().hasMessageContaining("violates row-level security policy");

        assertThat(as(alice, inbox::pending)).extracting(InboxEntry::description).containsExactly("Segredo da Alice");
    }

    private UUID add(TestUser user, com.selfcontrol.domain.transaction.TransactionType type, String amount,
                     String description, UUID category) {
        return as(user, () -> inbox.add(new InboxCommand(type, new BigDecimal(amount), null, DAY, category, description),
                ImportSource.WEBHOOK).id());
    }

    private static InboxCommand command(com.selfcontrol.domain.transaction.TransactionType type, String amount, UUID category) {
        return new InboxCommand(type, new BigDecimal(amount), null, DAY, category, "x");
    }

    private ResultActions bulk(TestUser user, String path, UUID... ids) throws Exception {
        var request = post(path).with(user.login()).with(csrf());
        for (UUID id : ids) {
            request.param("ids", id.toString());
        }
        return mvc.perform(request);
    }

    private int count(TestUser user, String sql) {
        return as(user, () -> jdbc.sql(sql).query(Integer.class).single());
    }

    private UUID category(TestUser user, String name, String type) throws Exception {
        String body = mvc.perform(json(post("/api/categories"), user,
                        "{\"name\": \"%s\", \"type\": \"%s\"}".formatted(name, type)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

}
