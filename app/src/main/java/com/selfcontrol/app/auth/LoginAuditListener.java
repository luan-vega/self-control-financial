package com.selfcontrol.app.auth;

import com.selfcontrol.app.shared.audit.AuditLog;
import com.selfcontrol.app.shared.security.AppPrincipal;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Registra logins (senha e Google) na trilha de auditoria e guarda o último acesso. Falhas também,
 * com o e-mail tentado, para dar para investigar tentativas de adivinhar senha.
 * Só os logins interativos passam por aqui; chave de API e token do webhook não geram evento.
 */
@Component
class LoginAuditListener {

    private final JdbcClient jdbc;
    private final AuditLog audit;

    LoginAuditListener(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @EventListener
    void success(AuthenticationSuccessEvent event) {
        if (!(event.getAuthentication().getPrincipal() instanceof AppPrincipal principal)) {
            return;
        }
        UUID id = principal.userId();
        jdbc.sql("UPDATE app_user SET last_login_at = now() WHERE id = :id").param("id", id).update();
        audit.record(id, id, "auth.login", "user", id,
                Map.of("method", event.getAuthentication().getClass().getSimpleName().contains("OAuth2") ? "google" : "password"));
    }

    @EventListener
    void failure(AbstractAuthenticationFailureEvent event) {
        String email = event.getAuthentication().getName();
        UUID subject = email == null ? null : jdbc.sql("SELECT id FROM app_user WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query(UUID.class)
                .optional()
                .orElse(null);
        audit.record(null, subject, "auth.login_failed", "user", subject,
                Map.of("email", email == null ? "" : email, "reason", event.getException().getClass().getSimpleName()));
    }
}
