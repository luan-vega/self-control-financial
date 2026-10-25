package com.selfcontrol.app.category;

import com.selfcontrol.domain.transaction.TransactionType;

/** Dados para criar ou alterar uma categoria, vindos do formulário ou da API. */
public record CategoryCommand(String name, TransactionType type, String color) {
}
