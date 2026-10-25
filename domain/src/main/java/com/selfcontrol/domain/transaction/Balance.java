package com.selfcontrol.domain.transaction;

import com.selfcontrol.domain.money.Money;

import java.util.Currency;

/** Resumo de receitas, despesas e saldo de um conjunto de lançamentos em uma moeda. */
public record Balance(Money income, Money expenses) {

    public static Balance of(Iterable<Transaction> transactions, Currency currency) {
        Money income = Money.zero(currency);
        Money expenses = Money.zero(currency);
        for (Transaction t : transactions) {
            if (t.type() == TransactionType.INCOME) {
                income = income.plus(t.amount());
            } else {
                expenses = expenses.plus(t.amount());
            }
        }
        return new Balance(income, expenses);
    }

    public Money net() {
        return income.minus(expenses);
    }
}
