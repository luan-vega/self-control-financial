package com.selfcontrol.domain.billing;

import java.util.Objects;
import java.util.OptionalInt;

/** Quantos lançamentos o usuário criou no mês, comparado ao limite do plano. */
public record Usage(Plan plan, int used) {

    public Usage {
        Objects.requireNonNull(plan, "plan");
        if (used < 0) {
            throw new IllegalArgumentException("Uso não pode ser negativo");
        }
    }

    public OptionalInt limit() {
        return plan.monthlyTransactionLimit();
    }

    public boolean unlimited() {
        return limit().isEmpty();
    }

    /** Cabe mais {@code count} lançamentos neste mês? */
    public boolean allows(int count) {
        return unlimited() || used + count <= limit().getAsInt();
    }

    /** Restantes no mês; Integer.MAX_VALUE quando ilimitado. */
    public int remaining() {
        return unlimited() ? Integer.MAX_VALUE : Math.max(0, limit().getAsInt() - used);
    }

    /** Percentual usado (0 a 100) para a barra de uso; 0 quando ilimitado. */
    public int percent() {
        if (unlimited()) {
            return 0;
        }
        return (int) Math.min(100, Math.round(used * 100.0 / limit().getAsInt()));
    }
}
