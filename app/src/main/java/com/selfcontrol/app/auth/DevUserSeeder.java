package com.selfcontrol.app.auth;

import com.selfcontrol.app.account.Legal;
import com.selfcontrol.app.category.CategoryClient;
import com.selfcontrol.app.shared.security.RunAs;
import com.selfcontrol.app.transaction.TransactionClient;
import com.selfcontrol.app.transaction.TransactionCommand;
import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.transaction.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.selfcontrol.domain.transaction.TransactionType.EXPENSE;
import static com.selfcontrol.domain.transaction.TransactionType.INCOME;

/**
 * Cria um usuário local para desenvolvimento (admin, e-mail confirmado, termos aceitos), com
 * categorias sugeridas e alguns lançamentos de exemplo no mês atual e no anterior. Só roda com o
 * perfil "dev".
 */
@Component
@Profile("dev")
class DevUserSeeder implements ApplicationRunner {

    static final String EMAIL = "dev@selfcontrol.local";
    static final String PASSWORD = "dev12345";

    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    private record Sample(String description, String category, TransactionType type, String amount, int day) {
    }

    private static final List<Sample> SAMPLES = List.of(
            new Sample("Salário", "Salário", INCOME, "8500.00", 5),
            new Sample("Aluguel", "Moradia", EXPENSE, "2200.00", 6),
            new Sample("Supermercado", "Mercado", EXPENSE, "643.90", 9),
            new Sample("Conta de luz", "Moradia", EXPENSE, "187.45", 12),
            new Sample("Uber", "Transporte", EXPENSE, "32.80", 14),
            new Sample("Freela site", "Extra", INCOME, "1200.00", 18),
            new Sample("iFood", "Restaurantes", EXPENSE, "89.90", 21),
            new Sample("Academia", "Saúde", EXPENSE, "129.90", 22));

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final CategoryClient categories;
    private final TransactionClient transactions;

    DevUserSeeder(JdbcClient jdbc, PasswordEncoder passwordEncoder,
                  CategoryClient categories, TransactionClient transactions) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.categories = categories;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        int created = jdbc.sql("""
                        INSERT INTO app_user (email, display_name, password_hash, role, email_verified_at,
                                              terms_accepted_at, terms_version)
                        VALUES (:email, 'Dev', :hash, 'ADMIN', now(), now(), :terms)
                        ON CONFLICT DO NOTHING
                        """)
                .param("email", EMAIL)
                .param("hash", passwordEncoder.encode(PASSWORD))
                .param("terms", Legal.VERSION)
                .update();
        if (created == 0) {
            return;
        }
        log.info("Usuário de desenvolvimento criado: {} / {}", EMAIL, PASSWORD);

        UUID userId = jdbc.sql("SELECT id FROM app_user WHERE email = :email")
                .param("email", EMAIL)
                .query(UUID.class)
                .single();
        RunAs.user(userId, EMAIL, () -> {
            Map<String, UUID> byName = categories.createDefaultsIfEmpty().stream()
                    .collect(Collectors.toMap(Category::name, Category::id, (a, b) -> a));
            YearMonth now = YearMonth.now();
            for (YearMonth month : List.of(now.minusMonths(1), now)) {
                for (Sample s : SAMPLES) {
                    LocalDate date = month.atDay(Math.min(s.day(), month.lengthOfMonth()));
                    if (date.isAfter(LocalDate.now())) {
                        continue;
                    }
                    transactions.create(new TransactionCommand(s.type(), new BigDecimal(s.amount()), null, date,
                            byName.get(s.category()), s.description()));
                }
            }
            return null;
        });
    }
}
