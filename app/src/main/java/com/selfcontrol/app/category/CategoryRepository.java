package com.selfcontrol.app.category;

import com.selfcontrol.domain.transaction.TransactionType;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.UUID;

/** Todas as consultas passam pelo RLS: só retornam categorias do usuário da transação. */
interface CategoryRepository extends ListCrudRepository<CategoryRow, UUID> {

    @Query("SELECT * FROM category ORDER BY type DESC, lower(name)")
    List<CategoryRow> findAllSorted();

    @Query("SELECT * FROM category WHERE type = :type ORDER BY lower(name)")
    List<CategoryRow> findByTypeSorted(TransactionType type);

    @Query("""
            SELECT EXISTS (SELECT 1 FROM category
                            WHERE type = :type AND lower(name) = lower(:name) AND id <> :exceptId)
            """)
    boolean nameTaken(TransactionType type, String name, UUID exceptId);

    @Query("SELECT EXISTS (SELECT 1 FROM financial_transaction WHERE category_id = :id)")
    boolean isInUse(UUID id);
}
