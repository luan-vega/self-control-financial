package com.selfcontrol.app.category;

import com.selfcontrol.app.shared.error.ConflictException;
import com.selfcontrol.app.shared.error.InvalidInputException;
import com.selfcontrol.app.shared.error.NotFoundException;
import com.selfcontrol.app.shared.security.UserContext;
import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.transaction.TransactionType;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.selfcontrol.domain.transaction.TransactionType.EXPENSE;
import static com.selfcontrol.domain.transaction.TransactionType.INCOME;

@Service
@Transactional
class CategoryService implements CategoryClient {

    private record Suggestion(String name, TransactionType type, String color) {
    }

    private static final List<Suggestion> DEFAULTS = List.of(
            new Suggestion("Salário", INCOME, "#059669"),
            new Suggestion("Extra", INCOME, "#0d9488"),
            new Suggestion("Moradia", EXPENSE, "#0ea5e9"),
            new Suggestion("Mercado", EXPENSE, "#10b981"),
            new Suggestion("Transporte", EXPENSE, "#f59e0b"),
            new Suggestion("Restaurantes", EXPENSE, "#f97316"),
            new Suggestion("Saúde", EXPENSE, "#ef4444"),
            new Suggestion("Lazer", EXPENSE, "#8b5cf6"),
            new Suggestion("Outros", EXPENSE, "#64748b"));

    private final CategoryRepository categories;
    private final JdbcAggregateTemplate aggregates;
    private final UserContext users;

    CategoryService(CategoryRepository categories, JdbcAggregateTemplate aggregates, UserContext users) {
        this.categories = categories;
        this.aggregates = aggregates;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> list() {
        return categories.findAllSorted().stream().map(CategoryRow::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> list(TransactionType type) {
        return categories.findByTypeSorted(type).stream().map(CategoryRow::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Category get(UUID id) {
        return categories.findById(id)
                .map(CategoryRow::toDomain)
                .orElseThrow(() -> new NotFoundException("Categoria não encontrada"));
    }

    @Override
    public Category create(CategoryCommand command) {
        Category category = build(UUID.randomUUID(), users.current().id(), command);
        requireUniqueName(category);
        aggregates.insert(CategoryRow.from(category));
        return category;
    }

    @Override
    public Category update(UUID id, CategoryCommand command) {
        Category current = get(id);
        Category changed = build(id, current.userId(), command);
        if (changed.type() != current.type() && categories.isInUse(id)) {
            throw new ConflictException("Não dá para trocar o tipo de uma categoria que já tem lançamentos");
        }
        requireUniqueName(changed);
        categories.save(CategoryRow.from(changed));
        return changed;
    }

    @Override
    public void delete(UUID id) {
        Category category = get(id);
        if (categories.isInUse(id)) {
            throw new ConflictException("A categoria \"" + category.name() + "\" tem lançamentos e não pode ser excluída");
        }
        categories.deleteById(id);
    }

    @Override
    public List<Category> createDefaultsIfEmpty() {
        if (categories.count() > 0) {
            return List.of();
        }
        UUID userId = users.current().id();
        List<Category> created = DEFAULTS.stream()
                .map(s -> new Category(UUID.randomUUID(), userId, s.name(), s.type(), s.color()))
                .toList();
        created.forEach(c -> aggregates.insert(CategoryRow.from(c)));
        return created;
    }

    private static Category build(UUID id, UUID userId, CategoryCommand command) {
        if (command.type() == null) {
            throw new InvalidInputException("type", "Escolha se a categoria é de receita ou despesa");
        }
        try {
            Category.normalizeColor(command.color());
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("color", "Cor inválida, use o formato #RRGGBB");
        }
        try {
            return new Category(id, userId, command.name(), command.type(), command.color());
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("name", e.getMessage());
        }
    }

    private void requireUniqueName(Category category) {
        if (categories.nameTaken(category.type(), category.name(), category.id())) {
            throw new ConflictException("Já existe uma categoria \"" + category.name() + "\" desse tipo");
        }
    }
}
