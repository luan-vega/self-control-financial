package com.selfcontrol.app.shared.audit;

import com.selfcontrol.app.shared.security.AppPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trilha de auditoria: grava quem fez o quê, quando e de onde (IP, user agent e canal).
 * Roda na transação de quem chama, então some junto se a operação falhar.
 * Nunca registre segredos (senhas, tokens, chaves) em details.
 */
@Component
public class AuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Ação do usuário logado sobre a própria conta. */
    public void record(String action, String targetType, Object targetId, Map<String, ?> details) {
        UUID actor = currentUserId();
        record(actor, actor, action, targetType, targetId, details);
    }

    /** actor fez a ação; subject é a conta afetada (iguais, salvo em ações de admin ou anônimas). */
    public void record(UUID actor, UUID subject, String action, String targetType, Object targetId,
                       Map<String, ?> details) {
        HttpServletRequest request = currentRequest();
        jdbc.sql("""
                        INSERT INTO audit_event (actor_id, subject_id, action, target_type, target_id, channel,
                                                 ip, user_agent, details)
                        VALUES (:actor, :subject, :action, :targetType, :targetId, :channel, :ip, :userAgent,
                                :details::jsonb)
                        """)
                .param("actor", actor)
                .param("subject", subject)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId == null ? null : targetId.toString())
                .param("channel", channel(request).name())
                .param("ip", request == null ? null : request.getRemoteAddr())
                .param("userAgent", request == null ? null : truncate(request.getHeader("User-Agent")))
                .param("details", json.writeValueAsString(details == null ? Map.of() : details))
                .update();
    }

    /** Eventos mais recentes; subject nulo traz de todas as contas (tela de admin). */
    public List<AuditEvent> recent(UUID subject, int limit) {
        return jdbc.sql("""
                        SELECT e.id, e.occurred_at, e.actor_id, a.email AS actor_email, e.subject_id,
                               s.email AS subject_email, e.action, e.target_type, e.target_id, e.channel, e.ip,
                               e.details::text AS details
                          FROM audit_event e
                          LEFT JOIN app_user a ON a.id = e.actor_id
                          LEFT JOIN app_user s ON s.id = e.subject_id
                         WHERE (CAST(:subject AS uuid) IS NULL OR e.subject_id = :subject)
                         ORDER BY e.id DESC
                         LIMIT :limit
                        """)
                .param("subject", subject)
                .param("limit", limit)
                .query((rs, n) -> new AuditEvent(rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getObject("actor_id", UUID.class), rs.getString("actor_email"),
                        rs.getObject("subject_id", UUID.class), rs.getString("subject_email"),
                        rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
                        Channel.valueOf(rs.getString("channel")), rs.getString("ip"), rs.getString("details")))
                .list();
    }

    static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AppPrincipal p ? p.userId() : null;
    }

    static Channel channel(HttpServletRequest request) {
        String uri = request == null ? null : request.getRequestURI();
        // Requisição HTTP de verdade sempre tem caminho; sem ele é tarefa interna (ou o request de fachada dos testes).
        if (uri == null || uri.isEmpty()) {
            return Channel.SYSTEM;
        }
        String path = uri.substring(request.getContextPath().length());
        if (path.startsWith("/api/v1/")) {
            return Channel.API;
        }
        if (path.endsWith("/webhook")) {
            return Channel.WEBHOOK;
        }
        return Channel.WEB;
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 300 ? value : value.substring(0, 300);
    }
}
