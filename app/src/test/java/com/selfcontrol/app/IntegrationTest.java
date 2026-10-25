package com.selfcontrol.app;

import com.selfcontrol.app.shared.security.AppUser;
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
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

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
    }

    protected TestUser newUser() {
        return newUser("BRL");
    }

    protected TestUser newUser(String currency) {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        UUID id = jdbc.sql("""
                        INSERT INTO app_user (email, display_name, default_currency)
                        VALUES (:email, 'Teste', :currency) RETURNING id
                        """)
                .param("email", email)
                .param("currency", currency)
                .query(UUID.class)
                .single();
        return new TestUser(id, email);
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
