package com.selfcontrol.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WebSecurityTests {

    @Autowired
    MockMvc mvc;

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
    }

    @Test
    @WithMockUser("luan@example.com")
    void signedInUserSeesDashboard() throws Exception {
        mvc.perform(get("/").param("month", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Outubro de 2026")))
                .andExpect(content().string(containsString("Saldo do mês")));
    }

    @Test
    @WithMockUser
    void htmxRequestGetsOnlyTheSummaryFragment() throws Exception {
        mvc.perform(get("/").param("month", "2026-09").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Setembro de 2026")))
                .andExpect(content().string(not(containsString("<html"))));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
