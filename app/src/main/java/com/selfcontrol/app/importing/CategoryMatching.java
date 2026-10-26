package com.selfcontrol.app.importing;

import com.selfcontrol.app.category.CategoryClient;
import com.selfcontrol.domain.transaction.TransactionType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Monta o {@link CategoryMatcher} do usuário atual: categorias + histórico de lançamentos categorizados. */
@Component
public class CategoryMatching {

    /** Lançamentos recentes considerados para "mesma descrição, mesma categoria". */
    static final int HISTORY_LIMIT = 5000;

    private final CategoryClient categories;
    private final JdbcClient jdbc;

    CategoryMatching(CategoryClient categories, JdbcClient jdbc) {
        this.categories = categories;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public CategoryMatcher load() {
        var history = jdbc.sql("""
                        SELECT description, type, category_id
                          FROM financial_transaction
                         WHERE category_id IS NOT NULL AND description <> ''
                         ORDER BY occurred_on DESC, created_at DESC
                         LIMIT :limit
                        """)
                .param("limit", HISTORY_LIMIT)
                .query((rs, n) -> new CategoryMatcher.Past(rs.getString("description"),
                        TransactionType.valueOf(rs.getString("type")),
                        rs.getObject("category_id", UUID.class)))
                .list();
        return new CategoryMatcher(categories.list(), history);
    }
}
