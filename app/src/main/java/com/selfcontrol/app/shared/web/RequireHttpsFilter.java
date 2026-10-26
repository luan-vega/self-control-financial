package com.selfcontrol.app.shared.web;

import com.selfcontrol.app.shared.error.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * HTTPS obrigatório (app.security.require-https, ligado no perfil prod). Atrás de um proxy que
 * termina o TLS, o X-Forwarded-Proto diz se a conexão original era HTTPS
 * (server.forward-headers-strategy=framework). GET/HEAD são redirecionados; os outros métodos
 * recebem 403, porque redirecionar um POST perderia o corpo e o segredo já teria viajado aberto.
 * O health check fica liberado: as probes do Kubernetes chamam por HTTP dentro do cluster.
 * O HSTS (Strict-Transport-Security) é enviado pelo Spring Security nas respostas HTTPS.
 */
public class RequireHttpsFilter extends OncePerRequestFilter {

    private final boolean enabled;

    public RequireHttpsFilter(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !enabled || request.isSecure() || path.startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            String target = UriComponentsBuilder.fromUriString(request.getRequestURL().toString())
                    .scheme("https")
                    .port(-1)
                    .query(request.getQueryString())
                    .build(true)
                    .toUriString();
            response.setStatus(HttpStatus.PERMANENT_REDIRECT.value());
            response.setHeader("Location", target);
            return;
        }
        Problems.write(response, HttpStatus.FORBIDDEN, "HTTPS obrigatório");
    }
}
