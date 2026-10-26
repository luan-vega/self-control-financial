package com.selfcontrol.app.account;

import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Perfil: nome, moeda padrão, foto, senha, confirmação de e-mail e aceite dos termos. */
class ProfileTests extends IntegrationTest {

    /** PNG 1x1 transparente. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    LogMailer mailer;

    @Test
    void updatesNameAndCurrency() throws Exception {
        TestUser user = newUser();
        mvc.perform(get("/settings/profile").with(user.login()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString(user.email()), containsString("confirmado"))));

        mvc.perform(post("/settings/profile").with(user.login()).with(csrf()).param("name", "Carla Dias").param("currency", "EUR"))
                .andExpect(redirectedUrl("/settings/profile"))
                .andExpect(flash().attribute("message", "Perfil salvo."));
        mvc.perform(get("/").with(user.login())).andExpect(content().string(allOf(containsString("Carla Dias"),
                containsString("CD"))));
        // A moeda padrão vale para a API e os relatórios
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + apiKey(user, true)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.currency").value("EUR"));

        mvc.perform(post("/settings/profile").with(user.login()).with(csrf()).param("name", " ").param("currency", "EUR"))
                .andExpect(content().string(containsString("Informe seu nome")));
        mvc.perform(post("/settings/profile").with(user.login()).with(csrf()).param("name", "Carla").param("currency", "BTC"))
                .andExpect(content().string(containsString("Escolha uma moeda")));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE subject_id = :id AND action = 'profile.updated'")
                .param("id", user.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void uploadsServesAndRemovesTheAvatar() throws Exception {
        TestUser user = newUser();
        TestUser other = newUser();
        mvc.perform(get("/settings/profile/avatar").with(user.login())).andExpect(status().isNotFound());

        mvc.perform(multipart("/settings/profile/avatar").file(new MockMultipartFile("avatar", "foto.png", "image/png", PNG))
                        .with(user.login()).with(csrf()))
                .andExpect(redirectedUrl("/settings/profile"))
                .andExpect(flash().attribute("message", "Foto atualizada."));
        mvc.perform(get("/settings/profile/avatar").with(user.login()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));
        // Cada um só vê a própria foto
        mvc.perform(get("/settings/profile/avatar").with(other.login())).andExpect(status().isNotFound());

        // Tipo vem dos bytes: um "png" que é HTML é recusado; arquivo grande também
        mvc.perform(multipart("/settings/profile/avatar").file(new MockMultipartFile("avatar", "x.png", "image/png",
                        "<script>alert(1)</script>".getBytes())).with(user.login()).with(csrf()))
                .andExpect(flash().attribute("avatarError", "Use uma imagem PNG, JPEG ou WebP"));
        mvc.perform(multipart("/settings/profile/avatar").file(new MockMultipartFile("avatar", "x.png", "image/png",
                        new byte[600 * 1024])).with(user.login()).with(csrf()))
                .andExpect(flash().attribute("avatarError", "A foto pode ter até 512 KB"));
        mvc.perform(multipart("/settings/profile/avatar").file(new MockMultipartFile("avatar", "x.png", "image/png",
                        new byte[0])).with(user.login()).with(csrf()))
                .andExpect(flash().attribute("avatarError", "Escolha uma imagem"));

        mvc.perform(post("/settings/profile/avatar/delete").with(user.login()).with(csrf()))
                .andExpect(redirectedUrl("/settings/profile"));
        mvc.perform(get("/settings/profile/avatar").with(user.login())).andExpect(status().isNotFound());
    }

    @Test
    void detectsImageTypesByMagicBytes() {
        assertThat(ProfileService.contentType(PNG)).hasValue("image/png");
        assertThat(ProfileService.contentType(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0})).hasValue("image/jpeg");
        assertThat(ProfileService.contentType("RIFF0000WEBPVP8 ".getBytes())).hasValue("image/webp");
        assertThat(ProfileService.contentType("GIF89a".getBytes())).isEmpty();
    }

    @Test
    void changesPassword() throws Exception {
        TestUser user = newUser();
        jdbc.sql("UPDATE app_user SET password_hash = :hash WHERE id = :id")
                .param("hash", passwords.encode("senha-atual-1")).param("id", user.id()).update();

        mvc.perform(post("/settings/password").with(user.login()).with(csrf())
                        .param("currentPassword", "errada").param("newPassword", "nova-senha-1"))
                .andExpect(flash().attribute("passwordError", "Senha atual incorreta"));
        mvc.perform(post("/settings/password").with(user.login()).with(csrf())
                        .param("currentPassword", "senha-atual-1").param("newPassword", "curta"))
                .andExpect(flash().attribute("passwordError", "A senha precisa ter pelo menos 8 caracteres"));
        mvc.perform(post("/settings/password").with(user.login()).with(csrf())
                        .param("currentPassword", "senha-atual-1").param("newPassword", "nova-senha-1"))
                .andExpect(flash().attribute("message", "Senha alterada."));
        String hash = jdbc.sql("SELECT password_hash FROM app_user WHERE id = :id").param("id", user.id()).query(String.class).single();
        assertThat(passwords.matches("nova-senha-1", hash)).isTrue();
    }

    @Test
    void googleOnlyAccountCanCreateAPassword() throws Exception {
        TestUser user = newUser();
        mvc.perform(get("/settings/profile").with(user.login())).andExpect(content().string(containsString("Criar senha")));
        mvc.perform(post("/settings/password").with(user.login()).with(csrf()).param("newPassword", "primeira-senha"))
                .andExpect(flash().attribute("message", "Senha alterada."));
    }

    @Test
    void resendsVerificationOnlyWhenPending() throws Exception {
        TestUser user = newUser();
        jdbc.sql("UPDATE app_user SET email_verified_at = NULL WHERE id = :id").param("id", user.id()).update();
        mvc.perform(get("/settings/profile").with(user.login())).andExpect(content().string(containsString("não confirmado")));

        mvc.perform(post("/settings/profile/verification").with(user.login()).with(csrf()))
                .andExpect(redirectedUrl("/settings/profile"));
        assertThat(mailer.recent().getFirst().to()).isEqualTo(user.email());

        TestUser verified = newUser();
        mvc.perform(post("/settings/profile/verification").with(verified.login()).with(csrf()));
        assertThat(mailer.recent()).noneMatch(m -> m.to().equals(verified.email()));
    }

    @Test
    void usersWithoutAcceptedTermsMustAcceptFirst() throws Exception {
        TestUser user = newUser();
        jdbc.sql("UPDATE app_user SET terms_version = NULL, terms_accepted_at = NULL WHERE id = :id")
                .param("id", user.id()).update();

        mvc.perform(get("/").with(user.login())).andExpect(redirectedUrl("/legal/accept"));
        mvc.perform(get("/transactions").header("HX-Request", "true").with(user.login()))
                .andExpect(header().string("HX-Redirect", "/legal/accept"));
        // A API não é bloqueada (quem cria chave já aceitou pelas telas)
        mvc.perform(get("/api/categories").with(user.login())).andExpect(status().isOk());

        mvc.perform(get("/legal/accept").with(user.login())).andExpect(content().string(containsString("Antes de continuar")));
        mvc.perform(post("/legal/accept").with(user.login()).with(csrf()))
                .andExpect(content().string(containsString("Marque a caixa")));
        mvc.perform(post("/legal/accept").with(user.login()).with(csrf()).param("acceptTerms", "true"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(get("/").with(user.login())).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT terms_version FROM app_user WHERE id = :id").param("id", user.id())
                .query(String.class).single()).isEqualTo(Legal.VERSION);
    }

    @Test
    void settingsHubShowsAdminLinkOnlyForAdmins() throws Exception {
        TestUser admin = newAdmin();
        mvc.perform(get("/settings").with(admin.adminLogin())).andExpect(content().string(containsString("Administração")));
        mvc.perform(get("/settings").with(newUser().login())).andExpect(content().string(not(containsString("Administração"))));
    }
}
