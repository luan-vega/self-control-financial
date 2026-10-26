package com.selfcontrol.app.webhook;

import com.jayway.jsonpath.JsonPath;
import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Webhook dos Atalhos: token secreto, sem sessão nem CSRF, e o lançamento cai na caixa do dono do token. */
class WebhookTests extends IntegrationTest {

    @Test
    void validTokenCreatesAPendingItemWithoutCsrfOrSession() throws Exception {
        TestUser user = newUser();
        String token = generateToken(user);

        webhook(token, """
                {"date": "2026-10-26", "description": "Padaria", "amount": "12,50", "type": "EXPENSE"}
                """)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value("12.50"))
                .andExpect(jsonPath("$.amountCents").value(1250))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.occurredOn").value("2026-10-26"))
                .andExpect(jsonPath("$.type").value("EXPENSE"));

        assertThat(count(user, "SELECT count(*) FROM inbox_item WHERE source = 'WEBHOOK' AND status = 'PENDING'")).isEqualTo(1);
        mvc.perform(get("/inbox").with(user.login()))
                .andExpect(content().string(allOf(containsString("Padaria"), containsString("Atalho do iPhone"))));
    }

    @Test
    void acceptsWhatShortcutsSends() throws Exception {
        TestUser user = newUser();
        UUID food = category(user, "Mercado", "EXPENSE");
        String token = generateToken(user);

        // Número em vez de texto, data ISO com hora e fuso, category_id e header alternativo
        mvc.perform(post(WebhookController.PATH).header(WebhookTokenFilter.TOKEN_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "2026-10-26T14:05:00-03:00", "description": "Pão de Açúcar", "amount": 89.9,
                                 "category_id": "%s"}
                                """.formatted(food)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.occurredOn").value("2026-10-26"))
                .andExpect(jsonPath("$.amountCents").value(8990))
                .andExpect(jsonPath("$.categoryName").value("Mercado"));

        // Só o valor: data de hoje, despesa, "R$" e sinal ignorados
        webhook(token, "{\"amount\": \"-R$ 1.234,56\", \"description\": \"Pão de Açúcar\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.occurredOn").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.type").value("EXPENSE"))
                .andExpect(jsonPath("$.amountCents").value(123456));

        webhook(token, "{\"amount\": \"100\", \"type\": \"receita\", \"date\": \"26/10/2026\"}")
                .andExpect(jsonPath("$.type").value("INCOME"));
    }

    @Test
    void mapsTheCategoryFromHistoryWhenNoneIsSent() throws Exception {
        TestUser user = newUser();
        UUID transport = category(user, "Transporte", "EXPENSE");
        mvc.perform(json(post("/api/transactions"), user, """
                        {"type": "EXPENSE", "amount": "30", "occurredOn": "2026-10-01", "categoryId": "%s", "description": "Uber"}
                        """.formatted(transport)))
                .andExpect(status().isCreated());
        String token = generateToken(user);

        webhook(token, "{\"amount\": \"25,10\", \"description\": \"UBER\"}")
                .andExpect(jsonPath("$.categoryId").value(transport.toString()));
        webhook(token, "{\"amount\": \"25,10\", \"description\": \"Outra coisa\"}")
                .andExpect(jsonPath("$.categoryId").doesNotExist());
    }

    @Test
    void rejectsMissingWrongAndRevokedTokens() throws Exception {
        TestUser user = newUser();
        String body = "{\"amount\": \"10\"}";

        mvc.perform(post(WebhookController.PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        webhook("scf_naoexiste", body).andExpect(status().isUnauthorized());
        webhook("outro-formato", body).andExpect(status().isUnauthorized());

        String token = generateToken(user);
        webhook(token, body).andExpect(status().isCreated());

        // Gerar de novo invalida o anterior
        String rotated = generateToken(user);
        webhook(token, body).andExpect(status().isUnauthorized());
        webhook(rotated, body).andExpect(status().isCreated());

        mvc.perform(delete("/settings/shortcut/token").with(user.login()).with(csrf()))
                .andExpect(content().string(containsString("Nenhum token ativo")));
        webhook(rotated, body).andExpect(status().isUnauthorized());
        assertThat(count(user, "SELECT count(*) FROM inbox_item")).isEqualTo(2);
    }

    @Test
    void tokenOnlyWorksForTheWebhookAndOnlyStoresAHash() throws Exception {
        TestUser user = newUser();
        String token = generateToken(user);

        // Não serve para a API da sessão
        mvc.perform(get("/api/transactions").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        String stored = jdbc.sql("SELECT token_hash FROM webhook_token WHERE user_id = :id")
                .param("id", user.id()).query(String.class).single();
        assertThat(stored).isNotEqualTo(token).isEqualTo(WebhookTokenService.hash(token)).hasSize(64);
    }

    @Test
    void validationErrorsUseTheWebhookFieldNames() throws Exception {
        TestUser user = newUser();
        UUID salary = category(user, "Salário", "INCOME");
        String token = generateToken(user);

        webhook(token, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amount").exists());
        webhook(token, "{\"amount\": \"abc\"}").andExpect(jsonPath("$.errors.amount").exists());
        webhook(token, "{\"amount\": \"0\"}").andExpect(jsonPath("$.errors.amount").exists());
        webhook(token, "{\"amount\": \"1\", \"date\": \"ontem\"}").andExpect(jsonPath("$.errors.date").exists());
        webhook(token, "{\"amount\": \"1\", \"date\": \"1800-01-01\"}").andExpect(jsonPath("$.errors.date").exists());
        webhook(token, "{\"amount\": \"1\", \"type\": \"talvez\"}").andExpect(jsonPath("$.errors.type").exists());
        webhook(token, "{\"amount\": \"1\", \"category_id\": \"x\"}").andExpect(jsonPath("$.errors.category_id").exists());
        webhook(token, "{\"amount\": \"1\", \"category_id\": \"%s\"}".formatted(salary))
                .andExpect(jsonPath("$.errors.category_id").exists());
        webhook(token, "{\"amount\": \"1\", \"category_id\": \"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(jsonPath("$.errors.category_id").exists());
        assertThat(count(user, "SELECT count(*) FROM inbox_item")).isZero();
    }

    @Test
    void itemsGoToTheTokenOwnerOnly() throws Exception {
        TestUser alice = newUser();
        TestUser bob = newUser();
        UUID bobCategory = category(bob, "Mercado", "EXPENSE");
        String aliceToken = generateToken(alice);

        // Alice não consegue usar a categoria do Bob
        webhook(aliceToken, "{\"amount\": \"1\", \"category_id\": \"%s\"}".formatted(bobCategory))
                .andExpect(jsonPath("$.errors.category_id").exists());
        webhook(aliceToken, "{\"amount\": \"1\", \"description\": \"Da Alice\"}").andExpect(status().isCreated());

        assertThat(count(alice, "SELECT count(*) FROM inbox_item")).isEqualTo(1);
        assertThat(count(bob, "SELECT count(*) FROM inbox_item")).isZero();
        mvc.perform(get("/settings/shortcut").with(bob.login()))
                .andExpect(content().string(allOf(containsString("Nenhum token ativo"), not(containsString(aliceToken)))));
    }

    @Test
    void settingsPageShowsUrlTokenStatusAndCategoryIds() throws Exception {
        TestUser user = newUser();
        UUID food = category(user, "Mercado", "EXPENSE");

        mvc.perform(get("/settings/shortcut").with(user.login()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("http://localhost/api/transactions/webhook"),
                        containsString("Nenhum token ativo"), containsString(food.toString()))));
        String token = generateToken(user);
        webhook(token, "{\"amount\": \"1\"}");

        mvc.perform(get("/settings/shortcut").with(user.login()))
                .andExpect(content().string(allOf(containsString(token.substring(token.length() - 4)),
                        containsString("Último uso"), not(containsString(token)))));
        mvc.perform(get("/settings/shortcut").header("HX-Request", "true").with(user.login()))
                .andExpect(content().string(not(containsString("<html"))));
    }

    private String generateToken(TestUser user) throws Exception {
        MvcResult result = mvc.perform(post("/settings/shortcut/token").with(user.login()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Copie agora")))
                .andReturn();
        Matcher m = Pattern.compile("value=\"(scf_[A-Za-z0-9_-]+)\"").matcher(result.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private ResultActions webhook(String token, String body) throws Exception {
        return mvc.perform(post(WebhookController.PATH).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
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
