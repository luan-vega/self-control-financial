package com.selfcontrol.app.category;

import com.jayway.jsonpath.JsonPath;
import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CategoryApiTests extends IntegrationTest {

    @Test
    void fullCrud() throws Exception {
        TestUser user = newUser();

        String body = mvc.perform(json(post("/api/categories"), user, """
                        {"name": " Mercado ", "type": "EXPENSE", "color": "#10B981"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/categories/")))
                .andExpect(jsonPath("$.name").value("Mercado"))
                .andExpect(jsonPath("$.color").value("#10b981"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/categories/{id}", id).with(user.login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("EXPENSE"));

        mvc.perform(json(put("/api/categories/{id}", id), user, """
                        {"name": "Supermercado", "type": "EXPENSE", "color": "#0ea5e9"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Supermercado"));

        mvc.perform(get("/api/categories").with(user.login()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].color").value("#0ea5e9"));

        mvc.perform(delete("/api/categories/{id}", id).with(user.login()).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/categories/{id}", id).with(user.login()))
                .andExpect(status().isNotFound());
    }

    @Test
    void filtersByType() throws Exception {
        TestUser user = newUser();
        create(user, "Salário", "INCOME");
        create(user, "Mercado", "EXPENSE");

        mvc.perform(get("/api/categories").param("type", "INCOME").with(user.login()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Salário"));
    }

    @Test
    void validatesInput() throws Exception {
        TestUser user = newUser();

        mvc.perform(json(post("/api/categories"), user, "{\"name\": \"\", \"type\": \"EXPENSE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        mvc.perform(json(post("/api/categories"), user, "{\"name\": \"Mercado\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.type").exists());
        mvc.perform(json(post("/api/categories"), user, "{\"name\": \"Mercado\", \"type\": \"EXPENSE\", \"color\": \"azul\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.color").exists());
    }

    @Test
    void rejectsDuplicateNamesIgnoringCase() throws Exception {
        TestUser user = newUser();
        create(user, "Mercado", "EXPENSE");

        mvc.perform(json(post("/api/categories"), user, "{\"name\": \"MERCADO\", \"type\": \"EXPENSE\"}"))
                .andExpect(status().isConflict());
        // Mesmo nome em outro tipo é permitido
        mvc.perform(json(post("/api/categories"), user, "{\"name\": \"Mercado\", \"type\": \"INCOME\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void categoryInUseCannotBeDeletedOrChangeType() throws Exception {
        TestUser user = newUser();
        String id = create(user, "Mercado", "EXPENSE");
        mvc.perform(json(post("/api/transactions"), user, """
                        {"type": "EXPENSE", "amount": "10.00", "occurredOn": "2026-10-01", "categoryId": "%s"}
                        """.formatted(id)))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/categories/{id}", id).with(user.login()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("tem lançamentos")));
        mvc.perform(json(put("/api/categories/{id}", id), user, "{\"name\": \"Mercado\", \"type\": \"INCOME\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void otherUsersCategoriesAreInvisible() throws Exception {
        TestUser alice = newUser();
        TestUser bob = newUser();
        String id = create(alice, "Mercado", "EXPENSE");

        mvc.perform(get("/api/categories").with(bob.login())).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/categories/{id}", id).with(bob.login())).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/categories/{id}", id), bob, "{\"name\": \"Hack\", \"type\": \"EXPENSE\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/categories/{id}", id).with(bob.login()).with(csrf()))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/categories/{id}", id).with(alice.login()))
                .andExpect(jsonPath("$.name").value("Mercado"));
    }

    private String create(TestUser user, String name, String type) throws Exception {
        String body = mvc.perform(json(post("/api/categories"), user,
                        "{\"name\": \"%s\", \"type\": \"%s\"}".formatted(name, type)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
