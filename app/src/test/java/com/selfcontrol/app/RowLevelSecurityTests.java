package com.selfcontrol.app;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Garante que as políticas de RLS isolam os dados de cada usuário e que o
 * RlsTransactionManager liga o usuário logado ao contexto do banco automaticamente.
 */
class RowLevelSecurityTests extends IntegrationTest {

    @Test
    void applicationConnectsWithARoleThatCannotBypassRls() {
        Boolean bypasses = jdbc.sql("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query(Boolean.class)
                .single();

        assertThat(bypasses).isFalse();
    }

    @Test
    void usersOnlySeeTheirOwnRows() {
        TestUser alice = newUser();
        TestUser bob = newUser();

        as(alice, () -> {
            UUID category = insertCategory("Mercado");
            return jdbc.sql("""
                            INSERT INTO financial_transaction (category_id, type, amount_cents, currency, occurred_on)
                            VALUES (:category, 'EXPENSE', 1500, 'BRL', current_date)
                            """)
                    .param("category", category)
                    .update();
        });

        int aliceTransactions = as(alice, this::countTransactions);
        int aliceCategories = as(alice, this::countCategories);
        int bobTransactions = as(bob, this::countTransactions);
        int bobCategories = as(bob, this::countCategories);
        int withoutUser = tx.execute(s -> countTransactions());

        assertThat(aliceTransactions).isEqualTo(1);
        assertThat(aliceCategories).isEqualTo(1);
        assertThat(bobTransactions).isZero();
        assertThat(bobCategories).isZero();
        assertThat(withoutUser).as("sem usuário no contexto").isZero();
    }

    @Test
    void contextIsResolvedByEmailWhenThePrincipalHasNoId() {
        TestUser alice = newUser();
        as(alice, () -> insertCategory("Moradia"));

        int seen = withMockPrincipal(alice.email(), () -> tx.execute(s -> countCategories()));

        assertThat(seen).isEqualTo(1);
    }

    @Test
    void cannotWriteRowsForAnotherUser() {
        TestUser alice = newUser();
        TestUser bob = newUser();

        assertThatThrownBy(() -> as(bob, () -> jdbc.sql("""
                        INSERT INTO category (user_id, name, type) VALUES (:user, 'Mercado', 'EXPENSE')
                        """)
                .param("user", alice.id())
                .update()))
                .rootCause()
                .hasMessageContaining("violates row-level security policy");
    }

    @Test
    void cannotPointATransactionToAnotherUsersCategory() {
        TestUser alice = newUser();
        TestUser bob = newUser();
        UUID aliceCategory = as(alice, () -> insertCategory("Mercado"));

        // A FK simples passaria (checagens de FK ignoram RLS); a FK composta (category_id, user_id) barra.
        assertThatThrownBy(() -> as(bob, () -> jdbc.sql("""
                        INSERT INTO financial_transaction (category_id, type, amount_cents, currency, occurred_on)
                        VALUES (:category, 'EXPENSE', 100, 'BRL', current_date)
                        """)
                .param("category", aliceCategory)
                .update()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void contextDoesNotLeakBetweenTransactionsOnThePooledConnection() {
        TestUser alice = newUser();
        as(alice, () -> insertCategory("Lazer"));

        int seen = as(alice, this::countCategories);
        String afterCommit = tx.execute(s -> jdbc.sql("SELECT coalesce(current_setting('app.current_user_id', true), '')")
                .query(String.class).single());

        assertThat(seen).isEqualTo(1);
        assertThat(afterCommit).isEmpty();
    }

    private UUID insertCategory(String name) {
        return jdbc.sql("INSERT INTO category (name, type) VALUES (:name, 'EXPENSE') RETURNING id")
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    private int countTransactions() {
        return jdbc.sql("SELECT count(*) FROM financial_transaction").query(Integer.class).single();
    }

    private int countCategories() {
        return jdbc.sql("SELECT count(*) FROM category").query(Integer.class).single();
    }

    private static <T> T withMockPrincipal(String email, Supplier<T> work) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken
                .authenticated(email, null, List.of()));
        var previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
