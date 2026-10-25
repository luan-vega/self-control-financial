package com.selfcontrol.app.dashboard;

import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.transaction.Balance;
import com.selfcontrol.domain.transaction.TransactionType;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public record DashboardView(YearMonth month, Balance balance, List<Entry> recent, List<CategoryTotal> topExpenses) {

    public record Entry(String description, String category, TransactionType type, Money amount, LocalDate date) {
    }

    /** share vai de 0 a 100 e alimenta a largura da barra no template. */
    public record CategoryTotal(String category, Money total, int share) {
    }
}
