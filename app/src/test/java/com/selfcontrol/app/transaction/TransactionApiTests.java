package com.selfcontrol.app.transaction;

import com.jayway.jsonpath.JsonPath;
import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransactionApiTests extends IntegrationTest {

    @Test
    void fullCrud() throws Exception {
        TestUser user = newUser();
        String food = category(user, "Mercado", "EXPENSE");
        String home = category(user, "Moradia", "EXPENSE");

        String body = mvc.perform(json(post("/api/transactions"), user, """
                        {"type": "EXPENSE", "amount": 123.45, "occurredOn": "2026-10-10",
                         "categoryId": "%s", "description": "  Supermercado  "}
                        """.formatted(food)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("123.45"))
                .andExpect(jsonPath("$.amountCents").value(12345))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.description").value("Supermercado"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/transactions/{id}", id).with(user.login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurredOn").value("2026-10-10"));

        mvc.perform(json(put("/api/transactions/{id}", id), user, """
                        {"type": "EXPENSE", "amount": "2200", "occurredOn": "2026-10-05", "categoryId": "%s",
                         "description": "Aluguel"}
                        """.formatted(home)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountCents").value(220000))
                .andExpect(jsonPath("$.categoryId").value(home));

        mvc.perform(get("/api/transactions").param("from", "2026-10-01").param("to", "2026-10-31").with(user.login()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].description").value("Aluguel"));

        mvc.perform(delete("/api/transactions/{id}", id).with(user.login()).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/transactions/{id}", id).with(user.login()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsByPeriodAndCategoryNewestFirst() throws Exception {
        TestUser user = newUser();
        String food = category(user, "Mercado", "EXPENSE");
        String salary = category(user, "Salário", "INCOME");
        create(user, "EXPENSE", "10", "2026-09-30", food);
        create(user, "EXPENSE", "20", "2026-10-02", food);
        create(user, "INCOME", "5000", "2026-10-05", salary);
        create(user, "EXPENSE", "30", "2026-10-20", food);

        mvc.perform(get("/api/transactions").param("from", "2026-10-01").param("to", "2026-10-31").with(user.login()))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].occurredOn").value("2026-10-20"))
                .andExpect(jsonPath("$[2].occurredOn").value("2026-10-02"));

        mvc.perform(get("/api/transactions").param("from", "2026-10-01").param("to", "2026-10-31")
                        .param("categoryId", food).with(user.login()))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void defaultsToTheCurrentMonth() throws Exception {
        TestUser user = newUser();
        String food = category(user, "Mercado", "EXPENSE");
        create(user, "EXPENSE", "10", java.time.LocalDate.now().toString(), food);
        create(user, "EXPENSE", "10", java.time.LocalDate.now().minusMonths(2).toString(), food);

        mvc.perform(get("/api/transactions").with(user.login())).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void validatesAmountDateAndCategory() throws Exception {
        TestUser user = newUser();
        String food = category(user, "Mercado", "EXPENSE");
        String salary = category(user, "Salário", "INCOME");

        expectFieldError(user, "{\"type\":\"EXPENSE\",\"amount\":0,\"occurredOn\":\"2026-10-01\",\"categoryId\":\"%s\"}".formatted(food), "amount");
        expectFieldError(user, "{\"type\":\"EXPENSE\",\"amount\":-5,\"occurredOn\":\"2026-10-01\",\"categoryId\":\"%s\"}".formatted(food), "amount");
        expectFieldError(user, "{\"type\":\"EXPENSE\",\"amount\":1.234,\"occurredOn\":\"2026-10-01\",\"categoryId\":\"%s\"}".formatted(food), "amount");
        expectFieldError(user, "{\"type\":\"EXPENSE\",\"amount\":10,\"occurredOn\":\"2026-10-01\"}", "categoryId");
        expectFieldError(user, "{\"type\":\"EXPENSE\",\"amount\":10,\"categoryId\":\"%s\"}".formatted(food), "occurredOn");
        expectFieldError(user, "{\"type\":\"EXPENSE\",\"amount\":10,\"occurredOn\":\"0026-10-01\",\"categoryId\":\"%s\"}".formatted(food), "occurredOn");
        expectFieldError(user, "{\"type\":\"EXPENSE\",\"amount\":10,\"occurredOn\":\"2026-10-01\",\"categoryId\":\"%s\"}".formatted(salary), "categoryId");
        expectFieldError(user, "{\"type\":\"EXPENSE\",\"amount\":10,\"occurredOn\":\"2026-10-01\",\"categoryId\":\"%s\",\"currency\":\"XYZ\"}".formatted(food), "currency");
        expectFieldError(user, "{\"amount\":10,\"occurredOn\":\"2026-10-01\",\"categoryId\":\"%s\"}".formatted(food), "type");

        // Data impossível nem chega a virar LocalDate
        mvc.perform(json(post("/api/transactions"), user,
                        "{\"type\":\"EXPENSE\",\"amount\":10,\"occurredOn\":\"2026-02-30\",\"categoryId\":\"%s\"}".formatted(food)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsOtherCurrenciesWithTheirOwnDecimals() throws Exception {
        TestUser user = newUser();
        String food = category(user, "Mercado", "EXPENSE");

        mvc.perform(json(post("/api/transactions"), user,
                        "{\"type\":\"EXPENSE\",\"amount\":500,\"currency\":\"jpy\",\"occurredOn\":\"2026-10-01\",\"categoryId\":\"%s\"}".formatted(food)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.amountCents").value(500));
        expectFieldError(user,
                "{\"type\":\"EXPENSE\",\"amount\":5.5,\"currency\":\"JPY\",\"occurredOn\":\"2026-10-01\",\"categoryId\":\"%s\"}".formatted(food),
                "amount");
    }

    @Test
    void otherUsersTransactionsAndCategoriesAreOffLimits() throws Exception {
        TestUser alice = newUser();
        TestUser bob = newUser();
        String aliceFood = category(alice, "Mercado", "EXPENSE");
        String id = create(alice, "EXPENSE", "99.90", "2026-10-01", aliceFood);

        mvc.perform(get("/api/transactions/{id}", id).with(bob.login())).andExpect(status().isNotFound());
        mvc.perform(get("/api/transactions").param("from", "2026-10-01").param("to", "2026-10-31").with(bob.login()))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(delete("/api/transactions/{id}", id).with(bob.login()).with(csrf())).andExpect(status().isNotFound());

        String bobFood = category(bob, "Mercado", "EXPENSE");
        mvc.perform(json(put("/api/transactions/{id}", id), bob,
                        "{\"type\":\"EXPENSE\",\"amount\":1,\"occurredOn\":\"2026-10-01\",\"categoryId\":\"%s\"}".formatted(bobFood)))
                .andExpect(status().isNotFound());
        // Bob também não consegue usar a categoria da Alice
        expectFieldError(bob, "{\"type\":\"EXPENSE\",\"amount\":1,\"occurredOn\":\"2026-10-01\",\"categoryId\":\"%s\"}".formatted(aliceFood), "categoryId");

        mvc.perform(get("/api/transactions/{id}", id).with(alice.login()))
                .andExpect(jsonPath("$.amount").value("99.90"));
    }

    @Test
    void invertedPeriodIsABadRequest() throws Exception {
        mvc.perform(get("/api/transactions").param("from", "2026-10-31").param("to", "2026-10-01").with(newUser().login()))
                .andExpect(status().isBadRequest());
    }

    private void expectFieldError(TestUser user, String body, String field) throws Exception {
        mvc.perform(json(post("/api/transactions"), user, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + field).exists());
    }

    private String category(TestUser user, String name, String type) throws Exception {
        String body = mvc.perform(json(post("/api/categories"), user,
                        "{\"name\": \"%s\", \"type\": \"%s\"}".formatted(name, type)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String create(TestUser user, String type, String amount, String date, String categoryId) throws Exception {
        String body = mvc.perform(json(post("/api/transactions"), user,
                        "{\"type\":\"%s\",\"amount\":\"%s\",\"occurredOn\":\"%s\",\"categoryId\":\"%s\"}"
                                .formatted(type, amount, date, categoryId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
