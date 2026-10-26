package com.selfcontrol.app.api;

import com.jayway.jsonpath.JsonPath;
import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Isolamento entre contas pela API pública: com a chave da Alice não dá para ver, alterar nem usar
 * nada do Bob. Quem garante é o RLS do Postgres (a API roda como o dono da chave), não filtros no código.
 */
class MultiUserIsolationTests extends IntegrationTest {

    @Test
    void oneAccountNeverSeesOrTouchesAnothersData() throws Exception {
        TestUser alice = newUser();
        TestUser bob = newUser();
        String aliceKey = apiKey(alice, false);
        String bobKey = apiKey(bob, false);

        String bobCategory = JsonPath.read(mvc.perform(api(post("/api/v1/categories"), bobKey,
                "{\"name\": \"Mercado\", \"type\": \"EXPENSE\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        String bobTx = JsonPath.read(mvc.perform(api(post("/api/v1/transactions"), bobKey, """
                        {"type": "EXPENSE", "amount": "500", "occurredOn": "2026-10-05", "categoryId": "%s",
                         "description": "Do Bob"}
                        """.formatted(bobCategory))).andReturn().getResponse().getContentAsString(), "$.id");
        String bobInbox = JsonPath.read(mvc.perform(api(post("/api/v1/inbox"), bobKey, """
                        {"type": "EXPENSE", "amount": "1", "occurredOn": "2026-10-05"}
                        """)).andReturn().getResponse().getContentAsString(), "$.id");

        // Listas da Alice vêm vazias
        mvc.perform(api(get("/api/v1/categories"), aliceKey)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(api(get("/api/v1/transactions").param("from", "2026-10-01").param("to", "2026-10-31"), aliceKey))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(api(get("/api/v1/inbox"), aliceKey)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(api(get("/api/v1/reports").param("from", "2026-10-01").param("to", "2026-10-31"), aliceKey))
                .andExpect(jsonPath("$.totals.expensesCents").value(0));

        // Acesso direto pelo id: 404, como se não existisse
        mvc.perform(api(get("/api/v1/transactions/" + bobTx), aliceKey)).andExpect(status().isNotFound());
        mvc.perform(api(get("/api/v1/categories/" + bobCategory), aliceKey)).andExpect(status().isNotFound());
        mvc.perform(api(get("/api/v1/inbox/" + bobInbox), aliceKey)).andExpect(status().isNotFound());
        mvc.perform(api(put("/api/v1/transactions/" + bobTx), aliceKey, """
                        {"type": "EXPENSE", "amount": "1", "occurredOn": "2026-10-05", "categoryId": "%s"}
                        """.formatted(bobCategory)))
                .andExpect(status().isNotFound());
        mvc.perform(api(delete("/api/v1/transactions/" + bobTx), aliceKey)).andExpect(status().isNotFound());
        mvc.perform(api(delete("/api/v1/categories/" + bobCategory), aliceKey)).andExpect(status().isNotFound());

        // Nem usar a categoria do Bob num lançamento da Alice
        mvc.perform(api(post("/api/v1/transactions"), aliceKey, """
                        {"type": "EXPENSE", "amount": "1", "occurredOn": "2026-10-05", "categoryId": "%s"}
                        """.formatted(bobCategory)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.categoryId").exists());

        // Confirmar/descartar ids do Bob não faz nada
        mvc.perform(api(post("/api/v1/inbox/discard"), aliceKey, "{\"ids\": [\"%s\"]}".formatted(bobInbox)))
                .andExpect(jsonPath("$.discarded").value(0));

        // O Bob continua com tudo
        mvc.perform(api(get("/api/v1/transactions/" + bobTx), bobKey)).andExpect(jsonPath("$.description").value("Do Bob"));
        mvc.perform(api(get("/api/v1/inbox"), bobKey)).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void adminIsJustAnotherUserForFinancialData() throws Exception {
        TestUser admin = newAdmin();
        TestUser user = newUser();
        String userKey = apiKey(user, false);
        mvc.perform(api(post("/api/v1/categories"), userKey, "{\"name\": \"Privada\", \"type\": \"EXPENSE\"}"))
                .andExpect(status().isCreated());

        mvc.perform(api(get("/api/v1/categories"), apiKey(admin, false))).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void apiKeysAreScopedToTheirOwner() throws Exception {
        TestUser alice = newUser();
        TestUser bob = newUser();
        apiKey(bob, false);

        // A tela de chaves da Alice não mostra a chave do Bob, e ela não consegue revogá-la
        String bobKeyId = jdbc.sql("SELECT id::text FROM api_key WHERE user_id = :id").param("id", bob.id())
                .query(String.class).single();
        mvc.perform(get("/settings/api-keys").with(alice.login()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("Nenhuma chave ativa")));
        mvc.perform(delete("/settings/api-keys/" + bobKeyId).with(alice.login())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound());
    }
}
