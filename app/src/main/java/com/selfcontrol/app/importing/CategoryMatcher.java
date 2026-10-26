package com.selfcontrol.app.importing;

import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.importing.Descriptions;
import com.selfcontrol.domain.transaction.TransactionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mapeamento automático de categorias, sem IA:
 * <ol>
 *   <li>pelo nome que veio no arquivo, comparado sem acento nem caixa com as categorias existentes;</li>
 *   <li>senão, pela categoria do lançamento mais recente com a mesma descrição;</li>
 *   <li>senão, fica sem categoria e o usuário escolhe na caixa de entrada.</li>
 * </ol>
 * O tipo (receita/despesa) vem do arquivo; se o arquivo não diz, da categoria; e por fim é despesa.
 */
public final class CategoryMatcher {

    /** type e category resolvidos; note explica por que uma categoria pedida não foi usada. */
    public record Match(TransactionType type, Category category, String note) {
    }

    /** Uma linha do histórico: lançamento já categorizado. */
    public record Past(String description, TransactionType type, UUID categoryId) {
    }

    private final Map<String, List<Category>> byName = new HashMap<>();
    private final Map<String, UUID> byDescription = new HashMap<>();
    private final Map<UUID, Category> byId = new HashMap<>();

    /** history deve vir do mais recente para o mais antigo: o primeiro de cada descrição vence. */
    public CategoryMatcher(List<Category> categories, List<Past> history) {
        for (Category c : categories) {
            byId.put(c.id(), c);
            byName.computeIfAbsent(Descriptions.key(c.name()), k -> new ArrayList<>()).add(c);
        }
        // Despesa primeiro: quando o nome existe nos dois tipos e o arquivo não diz o tipo.
        byName.values().forEach(list -> list.sort(Comparator.comparing(c -> c.type() == TransactionType.EXPENSE ? 0 : 1)));
        for (Past p : history) {
            byDescription.putIfAbsent(historyKey(p.description(), p.type()), p.categoryId());
        }
    }

    public Match resolve(String categoryName, TransactionType explicitType, String description) {
        Category category = null;
        String note = null;
        if (categoryName != null && !categoryName.isBlank()) {
            List<Category> named = byName.getOrDefault(Descriptions.key(categoryName), List.of());
            category = named.stream()
                    .filter(c -> explicitType == null || c.accepts(explicitType))
                    .findFirst().orElse(null);
            if (category == null) {
                note = named.isEmpty()
                        ? "Categoria \"" + categoryName.strip() + "\" não encontrada"
                        : "A categoria \"" + named.getFirst().name() + "\" é de "
                          + (named.getFirst().type() == TransactionType.INCOME ? "receitas" : "despesas");
            }
        }
        TransactionType type = explicitType != null ? explicitType : category != null ? category.type() : null;
        if (category == null) {
            category = fromHistory(description, type);
        }
        if (type == null) {
            type = category != null ? category.type() : TransactionType.EXPENSE;
        }
        return new Match(type, category, note);
    }

    /** Categoria do usuário por id, ou null se não existe (ou é de outro usuário). */
    public Category category(UUID id) {
        return id == null ? null : byId.get(id);
    }

    private Category fromHistory(String description, TransactionType type) {
        if (description == null || description.isBlank()) {
            return null;
        }
        List<TransactionType> types = type != null ? List.of(type)
                : List.of(TransactionType.EXPENSE, TransactionType.INCOME);
        for (TransactionType t : types) {
            Category c = byId.get(byDescription.get(historyKey(description, t)));
            if (c != null && c.accepts(t)) {
                return c;
            }
        }
        return null;
    }

    private static String historyKey(String description, TransactionType type) {
        return type + "|" + Descriptions.key(description);
    }
}
