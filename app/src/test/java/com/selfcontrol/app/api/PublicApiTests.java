package com.selfcontrol.app.api;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API pública /api/v1: CRUD em JSON com chave de API, sem sessão nem CSRF. */
class PublicApiTests extends IntegrationTest {

    @Autowired
    PasswordEncoder passwords;

    @Test
    void meIdentifiesTheKeyOwnerWithPlanAndUsage() throws Exception {
        TestUser user = newUser();
        String key = apiKey(user, false);

        mvc.perform(api(get("/api/v1/me"), key))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(header().string("X-RateLimit-Limit", "100"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.id").value(user.id().toString()))
                .andExpect(jsonPath("$.email").value(user.email()))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.usage.transactionsThisMonth").value(0))
                .andExpect(jsonPath("$.usage.monthlyLimit").value(100));
    }

    @Test
    void fullCrudOverJsonWithoutCsrf() throws Exception {
        TestUser user = newUser();
        String key = apiKey(user, false);

        // Categoria
        String categoryJson = mvc.perform(api(post("/api/v1/categories"), key, """
                        {"name": "Mercado", "type": "EXPENSE", "color": "#10b981"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/categories/")))
                .andReturn().getResponse().getContentAsString();
        String categoryId = JsonPath.read(categoryJson, "$.id");
        mvc.perform(api(put("/api/v1/categories/" + categoryId), key, """
                        {"name": "Supermercado", "type": "EXPENSE", "color": "#10b981"}
                        """))
                .andExpect(jsonPath("$.name").value("Supermercado"));

        // Lançamento
        String txJson = mvc.perform(api(post("/api/v1/transactions"), key, """
                        {"type": "EXPENSE", "amount": "123.45", "occurredOn": "2026-10-10", "categoryId": "%s",
                         "description": "Compra do mês"}
                        """.formatted(categoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountCents").value(12345))
                .andReturn().getResponse().getContentAsString();
        String txId = JsonPath.read(txJson, "$.id");

        mvc.perform(api(get("/api/v1/transactions/" + txId), key))
                .andExpect(jsonPath("$.description").value("Compra do mês"));
        mvc.perform(api(put("/api/v1/transactions/" + txId), key, """
                        {"type": "EXPENSE", "amount": "100", "occurredOn": "2026-10-11", "categoryId": "%s"}
                        """.formatted(categoryId)))
                .andExpect(jsonPath("$.amount").value("100.00"));
        mvc.perform(api(get("/api/v1/transactions").param("from", "2026-10-01").param("to", "2026-10-31"), key))
                .andExpect(jsonPath("$", hasSize(1)));

        // Relatório
        mvc.perform(api(get("/api/v1/reports").param("from", "2026-10-01").param("to", "2026-10-31"), key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.expensesCents").value(10000))
                .andExpect(jsonPath("$.expensesByCategory[0].name").value("Supermercado"));

        // Validação devolve problem+json com o campo
        mvc.perform(api(post("/api/v1/transactions"), key, "{\"type\": \"EXPENSE\", \"amount\": \"-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());

        mvc.perform(api(delete("/api/v1/transactions/" + txId), key)).andExpect(status().isNoContent());
        mvc.perform(api(get("/api/v1/transactions/" + txId), key)).andExpect(status().isNotFound());
        mvc.perform(api(delete("/api/v1/categories/" + categoryId), key)).andExpect(status().isNoContent());
    }

    @Test
    void inboxOverTheApi() throws Exception {
        TestUser user = newUser();
        String key = apiKey(user, false);
        String categoryId = JsonPath.read(mvc.perform(api(post("/api/v1/categories"), key,
                "{\"name\": \"Transporte\", \"type\": \"EXPENSE\"}")).andReturn().getResponse().getContentAsString(), "$.id");

        String first = JsonPath.read(mvc.perform(api(post("/api/v1/inbox"), key, """
                        {"type": "EXPENSE", "amount": "25.10", "occurredOn": "2026-10-20", "description": "Uber"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("API"))
                .andExpect(jsonPath("$.readyToConfirm").value(false))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String second = JsonPath.read(mvc.perform(api(post("/api/v1/inbox"), key, """
                        {"type": "EXPENSE", "amount": "9.90", "occurredOn": "2026-10-21", "description": "Café"}
                        """)).andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(api(get("/api/v1/inbox"), key)).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(api(put("/api/v1/inbox/" + first), key, """
                        {"type": "EXPENSE", "amount": "25.10", "occurredOn": "2026-10-20", "description": "Uber",
                         "categoryId": "%s"}
                        """.formatted(categoryId)))
                .andExpect(jsonPath("$.readyToConfirm").value(true));

        // O sem categoria fica na caixa
        mvc.perform(api(post("/api/v1/inbox/confirm"), key, "{\"ids\": [\"%s\", \"%s\"]}".formatted(first, second)))
                .andExpect(jsonPath("$.confirmed").value(1))
                .andExpect(jsonPath("$.skipped").value(1));
        mvc.perform(api(post("/api/v1/inbox/discard"), key, "{\"ids\": [\"%s\"]}".formatted(second)))
                .andExpect(jsonPath("$.discarded").value(1));
        mvc.perform(api(get("/api/v1/inbox"), key)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(api(post("/api/v1/inbox/confirm"), key, "{\"ids\": []}")).andExpect(status().isBadRequest());
        mvc.perform(api(get("/api/v1/transactions").param("from", "2026-10-01").param("to", "2026-10-31"), key))
                .andExpect(jsonPath("$[0].description").value("Uber"));
    }

    @Test
    void readOnlyKeyCanOnlyRead() throws Exception {
        TestUser user = newUser();
        String key = apiKey(user, true);

        mvc.perform(api(get("/api/v1/categories"), key)).andExpect(status().isOk());
        mvc.perform(api(post("/api/v1/categories"), key, "{\"name\": \"X\", \"type\": \"EXPENSE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("somente leitura")));
    }

    @Test
    void rejectsMissingInvalidAndRevokedKeys() throws Exception {
        TestUser user = newUser();

        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(api(get("/api/v1/me"), "scfk_naoexiste")).andExpect(status().isUnauthorized());
        mvc.perform(api(get("/api/v1/me"), "outro-formato")).andExpect(status().isUnauthorized());
        mvc.perform(api(get("/api/v1/me"), "a.b.c")).andExpect(status().isUnauthorized());

        String key = apiKey(user, false);
        mvc.perform(api(get("/api/v1/me"), key)).andExpect(status().isOk());
        UUID keyId = jdbc.sql("SELECT id FROM api_key WHERE user_id = :id").param("id", user.id()).query(UUID.class).single();
        as(user, () -> {
            apiKeys.revoke(keyId);
            return null;
        });
        mvc.perform(api(get("/api/v1/me"), key)).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionCookieAndWebhookTokenDoNotWorkOnThePublicApi() throws Exception {
        TestUser user = newUser();
        jdbc.sql("UPDATE app_user SET password_hash = :hash WHERE id = :id")
                .param("hash", passwords.encode("senha-123")).param("id", user.id()).update();
        // Spring Session (JDBC) devolve o cookie SESSION, como no navegador
        Cookie session = mvc.perform(post("/login").with(csrf())
                        .param("username", user.email()).param("password", "senha-123"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getCookie("SESSION");

        // Logado no navegador (sessão), mas a API pública só aceita o header Authorization
        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/").cookie(session)).andExpect(status().isOk());
        // E a chave de API não abre a API da sessão nem as telas
        String key = apiKey(user, false);
        mvc.perform(get("/api/transactions").header("Authorization", "Bearer " + key)).andExpect(status().isUnauthorized());
        mvc.perform(get("/").header("Authorization", "Bearer " + key)).andExpect(status().is3xxRedirection());
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() throws Exception {
        mvc.perform(options("/api/v1/transactions")
                        .header("Origin", "https://app.exemplo.com")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.exemplo.com"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        mvc.perform(options("/api/v1/transactions")
                        .header("Origin", "https://malicioso.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deactivatedAccountLosesApiAccess() throws Exception {
        TestUser user = newUser();
        String key = apiKey(user, false);
        jdbc.sql("UPDATE app_user SET active = false WHERE id = :id").param("id", user.id()).update();

        mvc.perform(api(get("/api/v1/me"), key)).andExpect(status().isUnauthorized());
    }
}
