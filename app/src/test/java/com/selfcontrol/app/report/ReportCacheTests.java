package com.selfcontrol.app.report;

import com.jayway.jsonpath.JsonPath;
import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Relatórios ficam em cache por usuário e saem dele quando um lançamento ou categoria muda. */
class ReportCacheTests extends IntegrationTest {

    @Autowired
    ReportCache cache;

    @Test
    void reportIsCachedUntilTheUserChangesSomething() throws Exception {
        TestUser user = newUser();
        String key = apiKey(user, false);
        String category = JsonPath.read(mvc.perform(api(post("/api/v1/categories"), key,
                "{\"name\": \"Casa\", \"type\": \"EXPENSE\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        create(key, category, "100");
        report(key, 10000);

        // Gravado direto no banco (sem passar pelo serviço): o cache ainda mostra o valor antigo
        as(user, () -> jdbc.sql("""
                        INSERT INTO financial_transaction (category_id, type, amount_cents, currency, occurred_on)
                        VALUES (:c, 'EXPENSE', 5000, 'BRL', DATE '2026-10-15')
                        """).param("c", UUID.fromString(category)).update());
        report(key, 10000);

        // Qualquer mudança pela app invalida
        String tx = create(key, category, "1");
        report(key, 15100);
        mvc.perform(api(delete("/api/v1/transactions/" + tx), key));
        report(key, 15000);
        mvc.perform(api(put("/api/v1/categories/" + category), key, "{\"name\": \"Moradia\", \"type\": \"EXPENSE\"}"));
        mvc.perform(api(get("/api/v1/reports").param("from", "2026-10-01").param("to", "2026-10-31"), key))
                .andExpect(jsonPath("$.expensesByCategory[0].name").value("Moradia"));
    }

    @Test
    void cacheIsPerUserAndPerQuery() {
        TestUser alice = newUser();
        TestUser bob = newUser();
        ReportCache.Key aliceKey = new ReportCache.Key(alice.id(), "report", "x");
        cache.get(aliceKey, () -> "da alice");
        cache.get(new ReportCache.Key(bob.id(), "report", "x"), () -> "do bob");

        assertThat(cache.<String>get(aliceKey, () -> "recalculado")).isEqualTo("da alice");
        cache.invalidate(alice.id());
        assertThat(cache.peek(aliceKey)).isEmpty();
        assertThat(cache.peek(new ReportCache.Key(bob.id(), "report", "x"))).hasValue("do bob");
        assertThat(cache.size()).isPositive();
        assertThat(aliceKey).isEqualTo(new ReportCache.Key(alice.id(), "report", "x"))
                .hasSameHashCodeAs(new ReportCache.Key(alice.id(), "report", "x"))
                .isNotEqualTo(new ReportCache.Key(alice.id(), "report", "y"));
    }

    @Test
    void canBeDisabled() {
        ReportCache disabled = new ReportCache(false, java.time.Duration.ofMinutes(1), 10);
        ReportCache.Key key = new ReportCache.Key(UUID.randomUUID(), "x");
        disabled.get(key, () -> "a");
        assertThat(disabled.<String>get(key, () -> "b")).isEqualTo("b");
    }

    private String create(String key, String category, String amount) throws Exception {
        return JsonPath.read(mvc.perform(api(post("/api/v1/transactions"), key, """
                        {"type": "EXPENSE", "amount": "%s", "occurredOn": "2026-10-10", "categoryId": "%s"}
                        """.formatted(amount, category))).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void report(String key, long expensesCents) throws Exception {
        mvc.perform(api(get("/api/v1/reports").param("from", "2026-10-01").param("to", "2026-10-31"), key))
                .andExpect(jsonPath("$.totals.expensesCents").value(expensesCents));
    }
}
