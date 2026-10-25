package com.selfcontrol.app.category;

import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.transaction.TransactionType;

import java.util.List;
import java.util.UUID;

/**
 * Porta de categorias usada pela camada web. Sempre age como o usuário logado (via RLS).
 * Hoje atendida em processo pelo {@link CategoryService}.
 */
public interface CategoryClient {

    List<Category> list();

    List<Category> list(TransactionType type);

    Category get(UUID id);

    Category create(CategoryCommand command);

    Category update(UUID id, CategoryCommand command);

    void delete(UUID id);

    /** Cria um conjunto inicial de categorias se o usuário ainda não tem nenhuma. */
    List<Category> createDefaultsIfEmpty();
}
