package com.selfcontrol.app;

import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebSecurityTests extends IntegrationTest {

    @Test
    void loginPageIsPublic() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Entrar")))
                .andExpect(content().string(not(containsString("Continuar com Google"))));
    }

    @Test
    void anonymousUserIsSentToLogin() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/transactions")).andExpect(status().is3xxRedirection());
    }

    @Test
    void anonymousApiCallGets401InsteadOfRedirect() throws Exception {
        mvc.perform(get("/api/transactions")).andExpect(status().isUnauthorized());
    }

    @Test
    void apiWritesRequireCsrfToken() throws Exception {
        TestUser user = newUser();
        mvc.perform(post("/api/categories").with(user.login())
                        .contentType("application/json")
                        .content("{\"name\":\"Mercado\",\"type\":\"EXPENSE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void passwordLoginWorksAgainstAppUser() throws Exception {
        jdbc.sql("""
                        INSERT INTO app_user (email, display_name, password_hash)
                        VALUES ('login@example.com', 'Login', :hash) ON CONFLICT DO NOTHING
                        """)
                .param("hash", new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2)
                        .encode("segredo123"))
                .update();

        mvc.perform(post("/login").with(csrf()).param("username", "LOGIN@example.com").param("password", "segredo123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        mvc.perform(post("/login").with(csrf()).param("username", "login@example.com").param("password", "errada"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void signedInUserSeesDashboard() throws Exception {
        mvc.perform(get("/").param("month", "2026-10").with(newUser().login()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Outubro de 2026")))
                .andExpect(content().string(containsString("Saldo do mês")));
    }

    @Test
    void htmxRequestGetsOnlyTheSummaryFragment() throws Exception {
        mvc.perform(get("/").param("month", "2026-09").header("HX-Request", "true").with(newUser().login()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Setembro de 2026")))
                .andExpect(content().string(not(containsString("<html"))));
    }

    @Test
    @WithMockUser("ghost@example.com")
    void authenticatedPrincipalWithoutAccountIsRejected() throws Exception {
        mvc.perform(post("/api/categories").with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"Mercado\",\"type\":\"EXPENSE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
