package com.selfcontrol.app.billing;

import com.jayway.jsonpath.JsonPath;
import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Plano Free com limite mensal, upgrade simulado para o Pro e tela de cobrança. */
class BillingTests extends IntegrationTest {

    @Test
    void freePlanStopsAt100TransactionsPerMonth() throws Exception {
        TestUser user = newUser();
        String key = apiKey(user, false);
        UUID category = category(key);
        fillMonth(user, category, 99);

        mvc.perform(api(post("/api/v1/transactions"), key, transaction(category))).andExpect(status().isCreated());
        mvc.perform(api(post("/api/v1/transactions"), key, transaction(category)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.limit").value(100))
                .andExpect(jsonPath("$.detail").value(containsString("plano Free")));

        // Na tela, o modal mostra o aviso com o link para os planos
        mvc.perform(post("/transactions").with(user.login()).with(csrf())
                        .param("type", "EXPENSE").param("amount", "10,00").param("occurredOn", "2026-10-10")
                        .param("categoryId", category.toString()))
                .andExpect(content().string(allOf(containsString("limite de 100"), containsString("Ver planos"))));

        // Aviso no início e uso na tela do plano
        mvc.perform(get("/").with(user.login())).andExpect(content().string(containsString("Conheça o Pro")));
        mvc.perform(get("/billing").with(user.login())).andExpect(content().string(containsString("100 de 100")));
    }

    @Test
    void inboxConfirmationRespectsTheLimitAllOrNothing() throws Exception {
        TestUser user = newUser();
        String key = apiKey(user, false);
        UUID category = category(key);
        fillMonth(user, category, 99);
        String a = inbox(key, category);
        String b = inbox(key, category);

        mvc.perform(api(post("/api/v1/inbox/confirm"), key, "{\"ids\": [\"%s\", \"%s\"]}".formatted(a, b)))
                .andExpect(status().isPaymentRequired());
        mvc.perform(api(get("/api/v1/inbox"), key)).andExpect(jsonPath("$.length()").value(2));

        mvc.perform(post("/inbox/confirm").with(user.login()).with(csrf()).param("ids", a, b))
                .andExpect(content().string(allOf(containsString("limite de 100"), containsString("Ver planos"))));
    }

    @Test
    void mockCheckoutUpgradesToProAndCancelGoesBackToFree() throws Exception {
        TestUser user = newUser();
        String key = apiKey(user, false);
        UUID category = category(key);
        fillMonth(user, category, 100);

        mvc.perform(get("/billing").with(user.login()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Assinar o Pro"), containsString("R$ 9,90/mês"),
                        containsString("Ambiente de teste"))));
        mvc.perform(post("/billing/checkout").with(user.login()).with(csrf()))
                .andExpect(redirectedUrl("http://localhost/billing/mock-checkout"));
        mvc.perform(get("/billing/mock-checkout").with(user.login()))
                .andExpect(content().string(containsString("Checkout simulado")));
        mvc.perform(post("/billing/mock-checkout").with(user.login()).with(csrf()))
                .andExpect(redirectedUrl("/billing?upgraded"));

        mvc.perform(get("/billing").param("upgraded", "").with(user.login()))
                .andExpect(content().string(allOf(containsString("Bem-vindo ao Pro"), containsString("Cancelar assinatura"),
                        not(containsString("Assinar o Pro")))));
        // Sem limite no Pro
        mvc.perform(api(post("/api/v1/transactions"), key, transaction(category))).andExpect(status().isCreated());
        mvc.perform(post("/billing/portal").with(user.login()).with(csrf()))
                .andExpect(redirectedUrl("http://localhost/billing"));

        mvc.perform(post("/billing/mock-cancel").with(user.login()).with(csrf()))
                .andExpect(redirectedUrl("/billing?downgraded"));
        mvc.perform(api(post("/api/v1/transactions"), key, transaction(category))).andExpect(status().isPaymentRequired());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE subject_id = :id AND action LIKE 'billing.%'")
                .param("id", user.id()).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void settingsHubShowsPlanAndLinks() throws Exception {
        TestUser user = newUser();
        mvc.perform(get("/settings").with(user.login()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Plano e cobrança"), containsString("Chaves de API"),
                        containsString("Free"), not(containsString("Administração")))));
    }

    /** Lançamentos criados neste mês direto no banco (mais rápido que pela API). */
    private void fillMonth(TestUser user, UUID category, int count) {
        as(user, () -> jdbc.sql("""
                        INSERT INTO financial_transaction (user_id, category_id, type, amount_cents, currency, occurred_on)
                        SELECT :user, :category, 'EXPENSE', 100, 'BRL', DATE '2026-10-01' FROM generate_series(1, :count)
                        """)
                .param("user", user.id())
                .param("category", category)
                .param("count", count)
                .update());
        // Um lançamento antigo (criado no mês passado) não conta para o limite
        as(user, () -> jdbc.sql("""
                        INSERT INTO financial_transaction (user_id, category_id, type, amount_cents, currency, occurred_on, created_at)
                        VALUES (:user, :category, 'EXPENSE', 100, 'BRL', DATE '2026-09-01', date_trunc('month', now()) - interval '1 day')
                        """)
                .param("user", user.id())
                .param("category", category)
                .update());
    }

    private UUID category(String key) throws Exception {
        return UUID.fromString(JsonPath.read(mvc.perform(api(post("/api/v1/categories"), key,
                "{\"name\": \"Mercado\", \"type\": \"EXPENSE\"}")).andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private String inbox(String key, UUID category) throws Exception {
        return JsonPath.read(mvc.perform(api(post("/api/v1/inbox"), key, """
                        {"type": "EXPENSE", "amount": "5", "occurredOn": "2026-10-15", "categoryId": "%s"}
                        """.formatted(category))).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String transaction(UUID category) {
        return """
                {"type": "EXPENSE", "amount": "10", "occurredOn": "2026-10-10", "categoryId": "%s"}
                """.formatted(category);
    }
}
