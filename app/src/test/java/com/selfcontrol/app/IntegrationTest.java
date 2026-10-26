package com.selfcontrol.app;

import com.selfcontrol.app.account.Legal;
import com.selfcontrol.app.apikey.ApiKeyService;
import com.selfcontrol.app.shared.security.AppUser;
import com.selfcontrol.app.shared.security.Role;
import com.selfcontrol.app.shared.security.RunAs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * Base dos testes de integração: app inteira + Postgres real (Testcontainers) conectado com um
 * usuário sem privilégios, então o RLS está sempre valendo. Cada teste cria usuários com e-mail
 * único, o que dispensa limpar o banco entre testes.
 */
@SpringBootTest(properties = {
        "app.billing.stripe.webhook-secret=" + IntegrationTest.STRIPE_SECRET,
        "app.api.cors.allowed-origins=https://app.exemplo.com"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    public static final String STRIPE_SECRET = "whsec_test_segredo";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TransactionTemplate tx;

    /** Usuário de teste com o id já resolvido, como no login real. */
    public record TestUser(UUID id, String email) {

        public RequestPostProcessor login() {
            return user(new AppUser(id, email, null));
        }

        /** Login com ROLE_ADMIN (a conta também precisa ser admin no banco: ver {@link #newAdmin()}). */
        public RequestPostProcessor adminLogin() {
            return user(new AppUser(id, email, null, Role.ADMIN, true));
        }
    }

    @Autowired
    protected ApiKeyService apiKeys;

    protected TestUser newUser() {
        return newUser("BRL");
    }

    protected TestUser newUser(String currency) {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        UUID id = jdbc.sql("""
                        INSERT INTO app_user (email, display_name, default_currency, email_verified_at,
                                              terms_accepted_at, terms_version)
                        VALUES (:email, 'Teste', :currency, now(), now(), :terms) RETURNING id
                        """)
                .param("email", email)
                .param("currency", currency)
                .param("terms", Legal.VERSION)
                .query(UUID.class)
                .single();
        return new TestUser(id, email);
    }

    protected TestUser newAdmin() {
        TestUser user = newUser();
        jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE id = :id").param("id", user.id()).update();
        return user;
    }

    /** Cria uma chave da API pública para o usuário e devolve o valor completo. */
    protected String apiKey(TestUser user, boolean readOnly) {
        return as(user, () -> apiKeys.create("Teste", readOnly).secret());
    }

    /** Requisição para a API pública (/api/v1) com chave, sem sessão nem CSRF. */
    protected static MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder request, String key) {
        return request.header("Authorization", "Bearer " + key).contentType(MediaType.APPLICATION_JSON);
    }

    protected static MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder request, String key, String body) {
        return api(request, key).content(body);
    }

    /** Roda um trecho numa transação como o usuário (contexto de RLS preenchido). */
    protected <T> T as(TestUser user, Supplier<T> work) {
        return RunAs.user(user.id(), user.email(), () -> tx.execute(status -> work.get()));
    }

    /** Requisição JSON autenticada com CSRF, como o navegador logado faria. */
    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, TestUser user, String body) {
        return request.with(user.login()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
