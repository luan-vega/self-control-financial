package com.selfcontrol.domain.category;

import com.selfcontrol.domain.transaction.TransactionType;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Categoria de lançamentos de um usuário. Cada categoria é só de receita ou só de despesa,
 * e tem uma cor (hex "#RRGGBB") usada nos gráficos.
 */
public record Category(UUID id, UUID userId, String name, TransactionType type, String color) {

    public static final String DEFAULT_COLOR = "#64748b";
    public static final int MAX_NAME_LENGTH = 60;

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-f]{6}$");

    public Category {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A categoria precisa de um nome");
        }
        name = name.strip();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("O nome da categoria deve ter até " + MAX_NAME_LENGTH + " caracteres");
        }
        color = normalizeColor(color);
    }

    /** Aceita "#RRGGBB" em qualquer caixa; vazio ou nulo vira a cor padrão. */
    public static String normalizeColor(String color) {
        if (color == null || color.isBlank()) {
            return DEFAULT_COLOR;
        }
        String normalized = color.strip().toLowerCase(Locale.ROOT);
        if (!HEX_COLOR.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Cor inválida, use o formato #RRGGBB: " + color);
        }
        return normalized;
    }

    /** Um lançamento só pode usar uma categoria do mesmo tipo (receita com receita, despesa com despesa). */
    public boolean accepts(TransactionType transactionType) {
        return type == transactionType;
    }
}
