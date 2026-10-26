package com.selfcontrol.app.admin;

import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Painel de admin: só para ROLE_ADMIN; ativar/desativar contas corta todos os acessos e fica auditado. */
class AdminTests extends IntegrationTest {

    @Autowired
    PasswordEncoder passwords;

    @Test
    void onlyAdminsGetIn() throws Exception {
        mvc.perform(get("/admin/users").with(newUser().login())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/users")).andExpect(status().is3xxRedirection());

        TestUser admin = newAdmin();
        TestUser someone = newUser();
        mvc.perform(get("/admin").with(admin.adminLogin())).andExpect(redirectedUrl("/admin/users"));
        mvc.perform(get("/admin/users").param("q", someone.email()).with(admin.adminLogin()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString(someone.email()), containsString("contas"),
                        not(containsString(admin.email())))));
        // Caracteres de LIKE na busca são literais
        mvc.perform(get("/admin/users").param("q", "%_%").with(admin.adminLogin()))
                .andExpect(content().string(containsString("Nenhuma conta encontrada")));
    }

    @Test
    void deactivatingCutsEveryKindOfAccess() throws Exception {
        TestUser admin = newAdmin();
        TestUser user = newUser();
        jdbc.sql("UPDATE app_user SET password_hash = :hash WHERE id = :id")
                .param("hash", passwords.encode("senha-123456")).param("id", user.id()).update();
        String key = apiKey(user, false);

        mvc.perform(post("/admin/users/{id}/active", user.id()).param("active", "false").with(admin.adminLogin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("desativada"), containsString("Ativar"))));

        mvc.perform(post("/login").with(csrf()).param("username", user.email()).param("password", "senha-123456"))
                .andExpect(redirectedUrl("/login?disabled"));
        mvc.perform(get("/login").param("disabled", "")).andExpect(content().string(containsString("desativada")));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + key)).andExpect(status().isUnauthorized());

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit_event
                         WHERE actor_id = :admin AND subject_id = :user AND action = 'admin.user_deactivated'
                        """)
                .param("admin", admin.id()).param("user", user.id()).query(Integer.class).single()).isEqualTo(1);

        // Reativar devolve o acesso
        mvc.perform(post("/admin/users/{id}/active", user.id()).param("active", "true").with(admin.adminLogin()).with(csrf()))
                .andExpect(content().string(containsString("Desativar")));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + key)).andExpect(status().isOk());
        mvc.perform(post("/login").with(csrf()).param("username", user.email()).param("password", "senha-123456"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void adminCannotDeactivateThemselves() throws Exception {
        TestUser admin = newAdmin();
        mvc.perform(post("/admin/users/{id}/active", admin.id()).param("active", "false").with(admin.adminLogin()).with(csrf()))
                .andExpect(content().string(containsString("não pode desativar a própria conta")));
        assertThat(jdbc.sql("SELECT active FROM app_user WHERE id = :id").param("id", admin.id()).query(Boolean.class).single())
                .isTrue();
    }

    @Test
    void auditPageShowsWhoDidWhat() throws Exception {
        TestUser admin = newAdmin();
        TestUser user = newUser();
        mvc.perform(post("/admin/users/{id}/active", user.id()).param("active", "false").with(admin.adminLogin()).with(csrf()));

        mvc.perform(get("/admin/audit").param("user", user.id().toString()).with(admin.adminLogin()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("admin.user_deactivated"), containsString(admin.email()))));
        mvc.perform(get("/admin/audit").with(admin.adminLogin())).andExpect(status().isOk());
    }
}
