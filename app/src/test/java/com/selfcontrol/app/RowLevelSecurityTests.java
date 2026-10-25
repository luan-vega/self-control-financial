package com.selfcontrol.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Garante que as políticas de RLS da V2 isolam os dados de cada usuário.
 * O usuário do container é superusuário (que ignora RLS), então as consultas rodam
 * com um papel comum, como o usuário da aplicação em produção.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RowLevelSecurityTests {

    private static final String APP_ROLE = "rls_app_user";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void createUnprivilegedRole() {
        jdbc.sql("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '%s') THEN
                        CREATE ROLE %s NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """.formatted(APP_ROLE, APP_ROLE)).update();
        jdbc.sql("GRANT SELECT, INSERT ON category, financial_transaction TO " + APP_ROLE).update();
    }

    @Test
    void usersOnlySeeTheirOwnRows() {
        UUID alice = createUser("alice@example.com");
        UUID bob = createUser("bob@example.com");

        asUser(alice, () -> jdbc.sql("""
                        INSERT INTO financial_transaction (user_id, type, amount_cents, currency, occurred_on)
                        VALUES (:user, 'EXPENSE', 1500, 'BRL', current_date)
                        """)
                .param("user", alice)
                .update());

        int seenByAlice = asUser(alice, this::countTransactions);
        int seenByBob = asUser(bob, this::countTransactions);
        int seenWithoutUser = tx.execute(s -> {
            jdbc.sql("SET LOCAL ROLE " + APP_ROLE).update();
            return countTransactions();
        });

        assertThat(seenByAlice).isEqualTo(1);
        assertThat(seenByBob).isZero();
        assertThat(seenWithoutUser).as("sem usuário no contexto").isZero();
    }

    @Test
    void cannotWriteRowsForAnotherUser() {
        UUID alice = createUser("alice2@example.com");
        UUID bob = createUser("bob2@example.com");

        assertThatThrownBy(() -> asUser(bob, () -> jdbc.sql("""
                        INSERT INTO category (user_id, name, type) VALUES (:user, 'Mercado', 'EXPENSE')
                        """)
                .param("user", alice)
                .update()))
                .rootCause()
                .hasMessageContaining("violates row-level security policy");
    }

    private int countTransactions() {
        return jdbc.sql("SELECT count(*) FROM financial_transaction").query(Integer.class).single();
    }

    private <T> T asUser(UUID userId, Supplier<T> work) {
        return tx.execute(status -> {
            jdbc.sql("SELECT set_config('app.current_user_id', :id, true)")
                    .param("id", userId.toString())
                    .query(String.class).single();
            jdbc.sql("SET LOCAL ROLE " + APP_ROLE).update();
            return work.get();
        });
    }

    private UUID createUser(String email) {
        return jdbc.sql("INSERT INTO app_user (email, display_name) VALUES (:email, 'Teste') RETURNING id")
                .param("email", email)
                .query(UUID.class)
                .single();
    }
}
