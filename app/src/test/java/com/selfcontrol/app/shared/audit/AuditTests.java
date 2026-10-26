package com.selfcontrol.app.shared.audit;

import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Trilha de auditoria: quem fez o quê, por qual canal, de qual IP. Só aceita inserts. */
class AuditTests extends IntegrationTest {

    @Autowired
    PasswordEncoder passwords;

    @Test
    void recordsApiChangesWithChannelAndIp() throws Exception {
        TestUser user = newUser();
        String key = apiKey(user, false);
        mvc.perform(api(post("/api/v1/categories"), key, "{\"name\": \"Lazer\", \"type\": \"EXPENSE\"}")
                .header("User-Agent", "app-teste/1.0")
                .with(request -> {
                    request.setRemoteAddr("198.51.100.4");
                    return request;
                }));

        var event = jdbc.sql("""
                        SELECT actor_id, channel, ip, user_agent, details->>'name' AS name FROM audit_event
                         WHERE subject_id = :id AND action = 'category.created'
                        """)
                .param("id", user.id())
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)})
                .single();
        assertThat(event).containsExactly(user.id().toString(), "API", "198.51.100.4", "app-teste/1.0", "Lazer");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE subject_id = :id AND action = 'api_key.created' AND channel = 'SYSTEM'")
                .param("id", user.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void recordsFailedLoginsWithTheAttemptedEmail() throws Exception {
        TestUser user = newUser();
        jdbc.sql("UPDATE app_user SET password_hash = :hash WHERE id = :id")
                .param("hash", passwords.encode("certa-123")).param("id", user.id()).update();

        mvc.perform(post("/login").with(csrf()).param("username", user.email()).param("password", "errada"));
        mvc.perform(post("/login").with(csrf()).param("username", "fantasma-" + UUID.randomUUID() + "@x.com").param("password", "x"));

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit_event
                         WHERE subject_id = :id AND actor_id IS NULL AND action = 'auth.login_failed' AND details->>'email' = :email
                        """)
                .param("id", user.id()).param("email", user.email()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void auditTrailIsAppendOnly() {
        TestUser user = newUser();
        jdbc.sql("INSERT INTO audit_event (subject_id, action, channel) VALUES (:id, 'teste', 'SYSTEM')")
                .param("id", user.id()).update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_event SET action = 'apagado' WHERE subject_id = :id")
                .param("id", user.id()).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("somente inserção");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_event WHERE subject_id = :id").param("id", user.id()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void deletingAnAccountKeepsItsAuditTrail() {
        TestUser user = newUser();
        jdbc.sql("INSERT INTO audit_event (actor_id, subject_id, action, channel) VALUES (:id, :id, 'teste', 'SYSTEM')")
                .param("id", user.id()).update();
        jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", user.id()).update();

        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'teste' AND subject_id IS NULL AND actor_id IS NULL")
                .query(Integer.class).single()).isPositive();
    }
}
