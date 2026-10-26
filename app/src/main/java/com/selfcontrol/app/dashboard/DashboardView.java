package com.selfcontrol.app.dashboard;

import com.selfcontrol.app.shared.web.Donut;
import com.selfcontrol.app.transaction.TransactionEntry;
import com.selfcontrol.domain.transaction.Balance;

import java.time.YearMonth;
import java.util.List;

/**
 * Tela inicial: saldo acumulado, receitas/despesas do mês, gastos por categoria (rosca) e
 * últimos lançamentos do mês.
 */
public record DashboardView(
        YearMonth month,
        Balance total,
        Balance balance,
        Donut expenses,
        List<TransactionEntry> recent,
        boolean hasCategories) {
}
