package com.selfcontrol.app.transaction;

import com.selfcontrol.domain.report.Period;
import com.selfcontrol.domain.transaction.Transaction;

import java.util.List;
import java.util.UUID;

/** Porta de lançamentos usada pela camada web. Sempre age como o usuário logado (via RLS). */
public interface TransactionClient {

    /** Lançamentos do período, mais recentes primeiro. categoryId nulo traz todas as categorias. */
    List<TransactionEntry> list(Period period, UUID categoryId);

    Transaction get(UUID id);

    Transaction create(TransactionCommand command);

    Transaction update(UUID id, TransactionCommand command);

    void delete(UUID id);
}
