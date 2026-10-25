package com.selfcontrol.app.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Cria um usuário local para desenvolvimento. Só roda com o perfil "dev". */
@Component
@Profile("dev")
class DevUserSeeder implements ApplicationRunner {

    static final String EMAIL = "dev@selfcontrol.local";
    static final String PASSWORD = "dev12345";

    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;

    DevUserSeeder(JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        int created = jdbc.sql("""
                        INSERT INTO app_user (email, display_name, password_hash)
                        VALUES (:email, 'Dev', :hash)
                        ON CONFLICT DO NOTHING
                        """)
                .param("email", EMAIL)
                .param("hash", passwordEncoder.encode(PASSWORD))
                .update();
        if (created > 0) {
            log.info("Usuário de desenvolvimento criado: {} / {}", EMAIL, PASSWORD);
        }
    }
}
