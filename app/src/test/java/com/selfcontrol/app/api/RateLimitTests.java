package com.selfcontrol.app.api;

import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Limites de requisições: 100/min por usuário na API pública e 10/min por IP nos formulários de login. */
class RateLimitTests extends IntegrationTest {

    @Test
    void apiAllows100RequestsPerMinutePerUser() throws Exception {
        TestUser user = newUser();
        // Duas chaves do mesmo usuário somam no mesmo limite
        String first = apiKey(user, true);
        String second = apiKey(user, true);

        for (int i = 0; i < 100; i++) {
            mvc.perform(api(get("/api/v1/categories"), i % 2 == 0 ? first : second))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(99 - i)));
        }
        mvc.perform(api(get("/api/v1/categories"), first))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.status").value(429));

        // Outro usuário não é afetado
        mvc.perform(api(get("/api/v1/categories"), apiKey(newUser(), true))).andExpect(status().isOk());
    }

    @Test
    void loginFormIsLimitedPerIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/login").with(csrf()).with(ip("203.0.113.7"))
                            .param("username", "ninguem@example.com").param("password", "errada"))
                    .andExpect(status().is3xxRedirection());
        }
        mvc.perform(post("/login").with(csrf()).with(ip("203.0.113.7"))
                        .param("username", "ninguem@example.com").param("password", "errada"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Outro IP segue normal, e as telas (GET) não contam
        mvc.perform(post("/login").with(csrf()).with(ip("203.0.113.8"))
                        .param("username", "ninguem@example.com").param("password", "errada"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/login").with(ip("203.0.113.7"))).andExpect(status().isOk());
    }

    @Test
    void webhookIsLimitedPerUser() throws Exception {
        TestUser user = newUser();
        String token = as(user, () -> webhookTokens.rotate());
        for (int i = 0; i < 60; i++) {
            mvc.perform(post("/api/transactions/webhook").header("Authorization", "Bearer " + token)
                            .contentType("application/json").content("{\"amount\": \"1\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/transactions/webhook").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"amount\": \"1\"}"))
                .andExpect(status().isTooManyRequests());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor ip(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.selfcontrol.app.webhook.WebhookTokenService webhookTokens;
}
