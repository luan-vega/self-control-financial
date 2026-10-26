package com.selfcontrol.domain.report;

import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.transaction.TransactionType;

import java.time.YearMonth;
import java.util.Objects;

/** Total de um tipo de lançamento em um mês. */
public record MonthlySum(YearMonth month, TransactionType type, Money total) {

    public MonthlySum {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(total, "total");
    }
}
