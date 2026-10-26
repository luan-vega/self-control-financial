package com.selfcontrol.app.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Quem é admin vem da configuração (app.admin.emails / ADMIN_EMAILS), não de uma tela: assim
 * ninguém se promove sozinho. Contas com esses e-mails viram admin ao subir a app e ao se cadastrar.
 */
@Component
public class AdminEmails implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminEmails.class);

    private final Set<String> emails;
    private final JdbcClient jdbc;

    AdminEmails(@Value("${app.admin.emails:}") String emails, JdbcClient jdbc) {
        this.emails = Arrays.stream(emails.split(","))
                .map(e -> e.strip().toLowerCase(Locale.ROOT))
                .filter(e -> !e.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.jdbc = jdbc;
    }

    public boolean isAdmin(String email) {
        return email != null && emails.contains(email.strip().toLowerCase(Locale.ROOT));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (emails.isEmpty()) {
            return;
        }
        int promoted = jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE role <> 'ADMIN' AND lower(email) IN (:emails)")
                .param("emails", emails)
                .update();
        if (promoted > 0) {
            log.info("{} conta(s) promovida(s) a admin por app.admin.emails", promoted);
        }
    }
}
