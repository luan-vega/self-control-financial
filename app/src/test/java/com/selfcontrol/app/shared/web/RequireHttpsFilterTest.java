package com.selfcontrol.app.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequireHttpsFilterTest {

    @Test
    void redirectsReadsAndRefusesWritesOverPlainHttp() throws Exception {
        RequireHttpsFilter filter = new RequireHttpsFilter(true);

        MockHttpServletRequest get = request("GET", "/reports");
        get.setQueryString("from=2026-10-01");
        MockHttpServletResponse redirected = run(filter, get);
        assertThat(redirected.getStatus()).isEqualTo(308);
        assertThat(redirected.getHeader("Location")).isEqualTo("https://app.exemplo.com/reports?from=2026-10-01");

        MockHttpServletResponse refused = run(filter, request("POST", "/api/v1/transactions"));
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(refused.getContentAsString()).contains("HTTPS obrigatório");
    }

    @Test
    void letsHttpsHealthChecksAndDisabledModeThrough() throws Exception {
        MockHttpServletRequest secure = request("POST", "/api/v1/transactions");
        secure.setSecure(true);
        secure.setScheme("https");
        assertThat(passes(new RequireHttpsFilter(true), secure)).isTrue();
        assertThat(passes(new RequireHttpsFilter(true), request("GET", "/actuator/health/liveness"))).isTrue();
        assertThat(passes(new RequireHttpsFilter(false), request("POST", "/login"))).isTrue();
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServerName("app.exemplo.com");
        request.setServerPort(80);
        return request;
    }

    private static MockHttpServletResponse run(RequireHttpsFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static boolean passes(RequireHttpsFilter filter, MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain.getRequest() != null;
    }
}
