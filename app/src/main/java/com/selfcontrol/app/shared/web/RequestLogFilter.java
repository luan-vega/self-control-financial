package com.selfcontrol.app.shared.web;

import com.selfcontrol.app.shared.security.AppPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Log de acesso estruturado: uma linha por requisição com método, caminho, status, duração,
 * usuário e um id de requisição (X-Request-Id, aceito do proxy ou gerado aqui). O id e o usuário
 * vão para o MDC, então aparecem também nos logs da própria requisição. Com
 * logging.structured.format.console=ecs (perfil prod) tudo sai em JSON. Query string e corpo não
 * são registrados, porque podem levar tokens (links de e-mail) e dados financeiros.
 */
public class RequestLogFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID = "X-Request-Id";
    static final String USER_ATTRIBUTE = RequestLogFilter.class.getName() + ".user";

    private static final Logger log = LoggerFactory.getLogger("http.access");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith(request.getContextPath() + "/css/") || path.startsWith(request.getContextPath() + "/js/")
                || path.startsWith(request.getContextPath() + "/icons/")
                || path.startsWith(request.getContextPath() + "/webjars/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_ID);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        response.setHeader(REQUEST_ID, requestId);
        MDC.put("requestId", requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000;
            Object user = request.getAttribute(USER_ATTRIBUTE);
            int status = response.getStatus();
            // As probes batem no health a cada poucos segundos: só aparecem em DEBUG quando dá tudo certo
            boolean probe = request.getRequestURI().startsWith("/actuator/health");
            var event = status >= 500 ? log.atError() : status >= 400 ? log.atWarn() : probe ? log.atDebug() : log.atInfo();
            event.addKeyValue("http.method", request.getMethod())
                    .addKeyValue("http.path", request.getRequestURI())
                    .addKeyValue("http.status", status)
                    .addKeyValue("http.duration_ms", millis)
                    .addKeyValue("client.ip", request.getRemoteAddr())
                    .addKeyValue("user.id", user)
                    .log("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), status, millis);
            MDC.remove("requestId");
            MDC.remove("userId");
        }
    }

    /** Anota o usuário autenticado na requisição (roda dentro da cadeia do Spring Security). */
    public static class UserTag extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AppPrincipal p) {
                request.setAttribute(USER_ATTRIBUTE, p.userId().toString());
                MDC.put("userId", p.userId().toString());
            }
            chain.doFilter(request, response);
        }
    }
}
