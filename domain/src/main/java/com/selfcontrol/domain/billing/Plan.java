package com.selfcontrol.domain.billing;

import java.util.OptionalInt;

/**
 * Planos da conta. O Free limita quantos lançamentos podem ser criados por mês (contando os
 * confirmados da caixa de entrada); o Pro não tem limite.
 */
public enum Plan {

    FREE("Free", 100),
    PRO("Pro", -1);

    private final String label;
    private final int monthlyTransactionLimit;

    Plan(String label, int monthlyTransactionLimit) {
        this.label = label;
        this.monthlyTransactionLimit = monthlyTransactionLimit;
    }

    public String label() {
        return label;
    }

    /** Vazio quando o plano é ilimitado. */
    public OptionalInt monthlyTransactionLimit() {
        return monthlyTransactionLimit < 0 ? OptionalInt.empty() : OptionalInt.of(monthlyTransactionLimit);
    }

    public Usage usage(int transactionsThisMonth) {
        return new Usage(this, transactionsThisMonth);
    }
}
