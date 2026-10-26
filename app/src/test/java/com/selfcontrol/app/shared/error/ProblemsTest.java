package com.selfcontrol.app.shared.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemsTest {

    @Test
    void writesEscapedProblemJson() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "aspas \" barra \\ linha\n tab\t \u0001");

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                        + "\"detail\":\"aspas \\\" barra \\\\ linha\\n tab\\t \\u0001\"}");
        assertThat(Problems.escape("a\rb")).isEqualTo("a\\rb");
    }
}
