package com.selfcontrol.app;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Postgres real para os testes, configurado como em produção: o mesmo script do compose.yaml
 * cria um usuário comum (sem superusuário nem BYPASSRLS) e a aplicação conecta com ele.
 * Assim o RLS vale de verdade em todos os testes, não só nos que trocam de papel.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String APP_DB = "selfcontrol";
    static final String APP_USER = "selfcontrol_app";
    static final String APP_PASSWORD = "selfcontrol_app";

    @Bean
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18-alpine")
                .withEnv("APP_DB_NAME", APP_DB)
                .withEnv("APP_DB_USER", APP_USER)
                .withEnv("APP_DB_PASSWORD", APP_PASSWORD)
                .withCopyFileToContainer(MountableFile.forHostPath("../docker/db/init-app-user.sh"),
                        "/docker-entrypoint-initdb.d/10-init-app-user.sh");
    }

    /**
     * Cada requisição do MockMvc sai de um IP diferente, para os limites por IP (login, cadastro,
     * API) não somarem as requisições de todos os testes. Os testes de rate limit fixam o IP.
     */
    @Bean
    MockMvcBuilderCustomizer distinctClientAddresses() {
        AtomicInteger counter = new AtomicInteger();
        return builder -> builder.defaultRequest(get("/").with(request -> {
            int n = counter.incrementAndGet();
            request.setRemoteAddr("10.%d.%d.%d".formatted((n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff));
            return request;
        }));
    }

    @Bean
    JdbcConnectionDetails appConnection(PostgreSQLContainer postgres) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return APP_USER;
            }

            @Override
            public String getPassword() {
                return APP_PASSWORD;
            }

            @Override
            public String getJdbcUrl() {
                if (!postgres.isRunning()) {
                    postgres.start();
                }
                return "jdbc:postgresql://%s:%d/%s".formatted(
                        postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), APP_DB);
            }
        };
    }
}
