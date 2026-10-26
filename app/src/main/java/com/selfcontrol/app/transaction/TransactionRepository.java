package com.selfcontrol.app.transaction;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Todas as consultas passam pelo RLS: só retornam lançamentos do usuário da transação. */
interface TransactionRepository extends ListCrudRepository<TransactionRow, UUID> {

    @Query("""
            SELECT * FROM financial_transaction
             WHERE occurred_on BETWEEN :from AND :to
             ORDER BY occurred_on DESC, created_at DESC
            """)
    List<TransactionRow> findInPeriod(LocalDate from, LocalDate to);

    @Query("""
            SELECT * FROM financial_transaction
             WHERE occurred_on BETWEEN :from AND :to AND category_id = :categoryId
             ORDER BY occurred_on DESC, created_at DESC
            """)
    List<TransactionRow> findInPeriodByCategory(LocalDate from, LocalDate to, UUID categoryId);
}
