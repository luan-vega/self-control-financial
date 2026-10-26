package com.selfcontrol.app.api;

import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Documentação OpenAPI pública e headers de segurança das telas. */
class OpenApiAndHeadersTests extends IntegrationTest {

    @Test
    void openApiDocumentsOnlyThePublicApi() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")))
                .andExpect(jsonPath("$.info.title").value("Self Control Financial API"))
                .andExpect(jsonPath("$.paths['/api/v1/transactions']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/inbox/confirm']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me']").exists())
                .andExpect(jsonPath("$.paths['/api/transactions']").doesNotExist())
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.scheme").value("bearer"));
        mvc.perform(get("/docs")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void pagesSendSecurityHeaders() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(header().string("Content-Security-Policy", allOf(containsString("script-src 'self'"),
                        containsString("frame-ancestors 'none'"), not(containsString("unsafe-eval")))))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().exists("X-Request-Id"));
        // HSTS só em HTTPS
        mvc.perform(get("/login").secure(true))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=")));
        // O id de requisição do proxy é reaproveitado; lixo é trocado por um novo
        mvc.perform(get("/login").header("X-Request-Id", "abc-123")).andExpect(header().string("X-Request-Id", "abc-123"));
        mvc.perform(get("/login").header("X-Request-Id", "<script>"))
                .andExpect(header().string("X-Request-Id", not(containsString("<"))));
    }

    @Test
    void noInlineScriptHandlersInTemplates() throws Exception {
        mvc.perform(get("/transactions/new").with(newUser().login()))
                .andExpect(content().string(not(containsString("hx-on"))));
    }
}
