package com.selfcontrol.app.auth;

import com.selfcontrol.app.IntegrationTest;
import com.selfcontrol.app.category.CategoryClient;
import com.selfcontrol.app.transaction.TransactionClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O seeder só roda com o perfil dev; aqui ele é chamado direto para não subir outro contexto. */
class DevUserSeederTests extends IntegrationTest {

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    CategoryClient categories;

    @Autowired
    TransactionClient transactions;

    @Test
    void seedsTheDevUserOnceWithCategoriesAndSamples() {
        var seeder = new DevUserSeeder(jdbc, passwordEncoder, categories, transactions);
        seeder.run(null);
        seeder.run(null);

        UUID id = jdbc.sql("SELECT id FROM app_user WHERE email = :email")
                .param("email", DevUserSeeder.EMAIL)
                .query(UUID.class)
                .single();
        TestUser dev = new TestUser(id, DevUserSeeder.EMAIL);
        int categoryCount = as(dev, () -> jdbc.sql("SELECT count(*) FROM category").query(Integer.class).single());
        int transactionCount = as(dev, () -> jdbc.sql("SELECT count(*) FROM financial_transaction").query(Integer.class).single());

        assertThat(categoryCount).isEqualTo(9);
        assertThat(transactionCount).isBetween(8, 16);
    }
}
