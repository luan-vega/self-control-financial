package com.selfcontrol.app.account;

import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cadastro aberto, confirmação de e-mail (mailer simulado) e recuperação de senha. */
class RegistrationTests extends IntegrationTest {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired
    LogMailer mailer;

    @Test
    void registerVerifyEmailAndLogIn() throws Exception {
        String email = "nova-" + UUID.randomUUID() + "@Example.COM";
        mvc.perform(get("/register")).andExpect(status().isOk()).andExpect(content().string(containsString("Criar conta")));

        mvc.perform(post("/register").with(csrf())
                        .param("name", "  Ana Souza ").param("email", email).param("password", "senha-forte-1")
                        .param("currency", "USD").param("acceptTerms", "true"))
                .andExpect(redirectedUrl("/login?registered"));

        String stored = email.replace("Example.COM", "example.com");
        var row = jdbc.sql("""
                        SELECT display_name, password_hash, default_currency, role, terms_version, email_verified_at IS NULL AS pending
                          FROM app_user WHERE email = :email
                        """)
                .param("email", stored)
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)})
                .single();
        assertThat(row[0]).isEqualTo("Ana Souza");
        assertThat(row[1]).startsWith("$argon2id$").doesNotContain("senha-forte-1");
        assertThat(row[2].strip()).isEqualTo("USD");
        assertThat(row[3]).isEqualTo("USER");
        assertThat(row[4]).isEqualTo(Legal.VERSION);
        assertThat(row[5]).isEqualTo("t");

        // O e-mail de confirmação foi "enviado" com o link
        Mailer.Message message = mailer.recent().getFirst();
        assertThat(message.to()).isEqualTo(stored);
        assertThat(message.subject()).contains("Confirme");
        String token = token(message.body());
        assertThat(message.body()).contains("http://localhost/verify-email?token=");

        mvc.perform(get("/verify-email").param("token", token))
                .andExpect(content().string(containsString("E-mail confirmado")));
        // Link usado não vale de novo
        mvc.perform(get("/verify-email").param("token", token))
                .andExpect(content().string(containsString("Link inválido ou vencido")));
        assertThat(jdbc.sql("SELECT email_verified_at IS NOT NULL FROM app_user WHERE email = :email")
                .param("email", stored).query(Boolean.class).single()).isTrue();

        mvc.perform(post("/login").with(csrf()).param("username", stored).param("password", "senha-forte-1"))
                .andExpect(redirectedUrl("/"));
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit_event e JOIN app_user u ON u.id = e.subject_id
                         WHERE u.email = :email AND e.action IN ('account.registered', 'account.email_verified', 'auth.login')
                        """)
                .param("email", stored).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT last_login_at IS NOT NULL FROM app_user WHERE email = :email")
                .param("email", stored).query(Boolean.class).single()).isTrue();
    }

    @Test
    void validatesTheForm() throws Exception {
        TestUser existing = newUser();

        register("Ana", existing.email().toUpperCase(), "senha-forte-1", "BRL", true)
                .andExpect(content().string(containsString("Já existe uma conta com este e-mail")));
        register("Ana", "sem-arroba", "senha-forte-1", "BRL", true)
                .andExpect(content().string(containsString("E-mail inválido")));
        register("Ana", "x-" + UUID.randomUUID() + "@example.com", "curta", "BRL", true)
                .andExpect(content().string(containsString("pelo menos 8 caracteres")));
        register("", "x-" + UUID.randomUUID() + "@example.com", "senha-forte-1", "BRL", true)
                .andExpect(content().string(containsString("Informe seu nome")));
        register("Ana", "x-" + UUID.randomUUID() + "@example.com", "senha-forte-1", "XYZ", true)
                .andExpect(content().string(containsString("Escolha uma moeda")));
        register("Ana", "x-" + UUID.randomUUID() + "@example.com", "senha-forte-1", "BRL", false)
                .andExpect(content().string(allOf(containsString("aceitar os Termos"), containsString("Criar conta"))));
    }

    @Test
    void forgotAndResetPassword() throws Exception {
        String email = "reset-" + UUID.randomUUID() + "@example.com";
        register("Bia", email, "senha-antiga-1", "BRL", true).andExpect(redirectedUrl("/login?registered"));

        // Mesma resposta para e-mail que existe e que não existe
        mvc.perform(post("/forgot-password").with(csrf()).param("email", "ninguem-" + UUID.randomUUID() + "@example.com"))
                .andExpect(content().string(containsString("Se existir uma conta")));
        mvc.perform(post("/forgot-password").with(csrf()).param("email", email))
                .andExpect(content().string(containsString("Se existir uma conta")));
        Mailer.Message message = mailer.recent().getFirst();
        assertThat(message.to()).isEqualTo(email);
        String token = token(message.body());

        mvc.perform(get("/reset-password").param("token", "invalido")).andExpect(content().string(containsString("venceu")));
        mvc.perform(get("/reset-password").param("token", token)).andExpect(content().string(containsString("Salvar nova senha")));
        mvc.perform(post("/reset-password").with(csrf()).param("token", token).param("password", "curta"))
                .andExpect(content().string(containsString("pelo menos 8")));
        mvc.perform(post("/reset-password").with(csrf()).param("token", token).param("password", "senha-nova-22"))
                .andExpect(redirectedUrl("/login?reset"));
        mvc.perform(post("/reset-password").with(csrf()).param("token", token).param("password", "senha-nova-33"))
                .andExpect(content().string(containsString("venceu")));

        mvc.perform(post("/login").with(csrf()).param("username", email).param("password", "senha-antiga-1"))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).param("username", email).param("password", "senha-nova-22"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(get("/login").param("reset", "")).andExpect(content().string(containsString("Senha alterada")));
    }

    @Test
    void publicPagesAndLoginLinks() throws Exception {
        mvc.perform(get("/login")).andExpect(content().string(allOf(containsString("Criar conta grátis"),
                containsString("Esqueci minha senha"))));
        mvc.perform(get("/legal/terms")).andExpect(content().string(allOf(containsString("Termos de Uso"),
                containsString(Legal.VERSION))));
        mvc.perform(get("/legal/privacy")).andExpect(content().string(containsString("LGPD")));
        mvc.perform(get("/forgot-password")).andExpect(status().isOk());
    }

    @Test
    void adminEmailsAreCaseInsensitive() {
        AdminEmails admins = new AdminEmails(" Chefe@Example.com, , outra@example.com", jdbc);
        assertThat(admins.isAdmin("chefe@example.com")).isTrue();
        assertThat(admins.isAdmin("OUTRA@example.com ")).isTrue();
        assertThat(admins.isAdmin("ninguem@example.com")).isFalse();
        assertThat(admins.isAdmin(null)).isFalse();
    }

    @Test
    void adminEmailsArePromotedOnStartup() throws Exception {
        TestUser user = newUser();
        new AdminEmails(user.email().toUpperCase(), jdbc).run(null);
        assertThat(jdbc.sql("SELECT role FROM app_user WHERE id = :id").param("id", user.id()).query(String.class).single())
                .isEqualTo("ADMIN");
    }

    private org.springframework.test.web.servlet.ResultActions register(String name, String email, String password,
                                                                         String currency, boolean terms) throws Exception {
        return mvc.perform(post("/register").with(csrf())
                .param("name", name).param("email", email).param("password", password)
                .param("currency", currency).param("acceptTerms", Boolean.toString(terms)));
    }

    private static String token(String body) {
        Matcher m = TOKEN.matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
