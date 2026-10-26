package com.selfcontrol.app.shared.db;

import com.selfcontrol.app.shared.security.AppPrincipal;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/**
 * Liga o Row-Level Security à sessão: no início de toda transação, grava o usuário logado em
 * {@code app.current_user_id} (escopo da transação, então some ao devolver a conexão ao pool).
 * Sem usuário logado nada é gravado e as políticas não retornam nenhuma linha.
 * <p>
 * Quando o principal não traz o id (ex.: {@code @WithMockUser} nos testes), o id é buscado
 * pelo e-mail na mesma instrução.
 * <p>
 * Ler o SecurityContext pode carregar a sessão do banco (o contexto é carregado sob demanda),
 * o que abre outra transação. A trava por thread evita que essa transação aninhada tente ler
 * o contexto de novo e entre em recursão.
 */
public class RlsTransactionManager extends JdbcTransactionManager {

    static final String SET_CURRENT_USER = """
            SELECT set_config('app.current_user_id',
                   coalesce(?::uuid, (SELECT id FROM app_user WHERE lower(email) = lower(?)))::text,
                   true)
            """;

    private static final ThreadLocal<Boolean> RESOLVING = ThreadLocal.withInitial(() -> false);

    public RlsTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        if (RESOLVING.get()) {
            return;
        }

        Authentication auth;
        RESOLVING.set(true);
        try {
            auth = SecurityContextHolder.getContext().getAuthentication();
        } finally {
            RESOLVING.remove();
        }
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return;
        }
        UUID userId = auth.getPrincipal() instanceof AppPrincipal p ? p.userId() : null;
        try (PreparedStatement ps = con.prepareStatement(SET_CURRENT_USER)) {
            ps.setObject(1, userId, Types.OTHER);
            ps.setString(2, auth.getName());
            ps.execute();
        }
    }
}
