package com.selfcontrol.app.shared.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** O plano atual não permite a operação (ex.: limite mensal de lançamentos do Free). */
@ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
public class PlanLimitException extends RuntimeException {

    private final int limit;

    public PlanLimitException(int limit) {
        super("Você chegou ao limite de " + limit + " lançamentos por mês do plano Free. "
                + "Faça upgrade para o Pro para lançar sem limite.");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
