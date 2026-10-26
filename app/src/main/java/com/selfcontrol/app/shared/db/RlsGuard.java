package com.selfcontrol.app.shared.db;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Recusa subir se o usuário do banco ignora RLS (superusuário ou BYPASSRLS): nesse caso todo
 * mundo veria os dados de todo mundo. Desligue com app.rls.require-enforced=false só se souber o que faz.
 */
@Component
class RlsGuard implements ApplicationRunner {

    private final JdbcClient jdbc;
    private final boolean required;

    RlsGuard(JdbcClient jdbc, @Value("${app.rls.require-enforced:true}") boolean required) {
        this.jdbc = jdbc;
        this.required = required;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean bypasses = jdbc.sql("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query(Boolean.class)
                .single();
        if (bypasses && required) {
            throw new IllegalStateException("""
                    O usuário do banco é superusuário ou tem BYPASSRLS, então o Row-Level Security \
                    não isolaria os dados. Use um usuário comum (veja docker/db/init-app-user.sh).""");
        }
    }
}
