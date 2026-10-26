package com.selfcontrol.app.admin;

import com.selfcontrol.app.shared.audit.AuditLog;
import com.selfcontrol.app.shared.error.ConflictException;
import com.selfcontrol.app.shared.error.NotFoundException;
import com.selfcontrol.app.shared.security.Role;
import com.selfcontrol.app.shared.security.Sessions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administração simples de contas: listar, buscar, ativar e desativar. Não enxerga dados
 * financeiros de ninguém (as tabelas financeiras têm RLS e o admin é um usuário como os outros);
 * só app_user e subscription, que não têm RLS. Toda ação vai para a trilha de auditoria.
 */
@Service
@Transactional
public class AdminService {

    public record Stats(int users, int active, int pro, int newLast7Days) {
    }

    static final int PAGE = 50;

    private static final String SELECT = """
            SELECT u.id, u.email, u.display_name, u.role, u.active, u.email_verified_at IS NOT NULL AS verified,
                   CASE WHEN s.plan = 'PRO' AND (s.status <> 'CANCELED' OR s.current_period_end > now())
                        THEN 'PRO' ELSE 'FREE' END AS plan,
                   u.created_at, u.last_login_at
              FROM app_user u
              LEFT JOIN subscription s ON s.user_id = u.id
            """;

    private final JdbcClient jdbc;
    private final Sessions sessions;
    private final AuditLog audit;

    AdminService(JdbcClient jdbc, Sessions sessions, AuditLog audit) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        return jdbc.sql("""
                        SELECT count(*) AS users,
                               count(*) FILTER (WHERE active) AS active,
                               count(*) FILTER (WHERE created_at > now() - interval '7 days') AS recent,
                               (SELECT count(*) FROM subscription
                                 WHERE plan = 'PRO' AND (status <> 'CANCELED' OR current_period_end > now())) AS pro
                          FROM app_user
                        """)
                .query((rs, n) -> new Stats(rs.getInt("users"), rs.getInt("active"), rs.getInt("pro"), rs.getInt("recent")))
                .single();
    }

    /** Busca por e-mail ou nome; mais recentes primeiro, de 50 em 50. */
    @Transactional(readOnly = true)
    public List<AdminUser> search(String query, int page) {
        String q = query == null ? "" : query.strip();
        return jdbc.sql(SELECT + """
                         WHERE (:q = '' OR u.email ILIKE '%' || :q || '%' OR u.display_name ILIKE '%' || :q || '%')
                         ORDER BY u.created_at DESC, u.id
                         LIMIT :limit OFFSET :offset
                        """)
                .param("q", q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"))
                .param("limit", PAGE + 1)
                .param("offset", Math.max(0, page) * PAGE)
                .query(AdminService::map)
                .list();
    }

    @Transactional(readOnly = true)
    public AdminUser get(UUID id) {
        return jdbc.sql(SELECT + " WHERE u.id = :id")
                .param("id", id)
                .query(AdminService::map)
                .optional()
                .orElseThrow(() -> new NotFoundException("Usuário não encontrado"));
    }

    /** Desativa: não entra mais (senha, Google, chave de API, webhook) e as sessões abertas caem. */
    public AdminUser setActive(UUID adminId, UUID userId, boolean active) {
        if (adminId.equals(userId) && !active) {
            throw new ConflictException("Você não pode desativar a própria conta");
        }
        AdminUser user = get(userId);
        if (user.active() == active) {
            return user;
        }
        jdbc.sql("UPDATE app_user SET active = :active, updated_at = now() WHERE id = :id")
                .param("active", active)
                .param("id", userId)
                .update();
        if (!active) {
            sessions.endAll(user.email());
        }
        audit.record(adminId, userId, active ? "admin.user_activated" : "admin.user_deactivated", "user", userId,
                Map.of("email", user.email()));
        return get(userId);
    }

    private static AdminUser map(ResultSet rs, int n) throws SQLException {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        return new AdminUser(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                Role.valueOf(rs.getString("role")), rs.getBoolean("active"), rs.getBoolean("verified"),
                rs.getString("plan"), rs.getTimestamp("created_at").toInstant(),
                lastLogin == null ? null : lastLogin.toInstant());
    }
}
