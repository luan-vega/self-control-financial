package com.selfcontrol.app.importing;

import com.selfcontrol.domain.importing.Fingerprint;
import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.transaction.TransactionType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL de import_batch e import_row. Tudo passa pelo RLS: user_id vem do contexto da transação
 * (default da coluna) e as leituras só enxergam os imports do usuário.
 */
@Repository
class ImportRepository {

    /** Linha pronta para gravar. amountCents/type/date/currency nulos em linhas com erro. */
    record NewRow(UUID id, int lineNo, RowStatus status, TransactionType type, Long amountCents, String currency,
                  LocalDate occurredOn, String description, UUID categoryId, String externalId, String message) {
    }

    /** import_row lida do banco; a categoria é resolvida pelo serviço. */
    record StoredRow(UUID id, int lineNo, RowStatus status, TransactionType type, Money amount, LocalDate occurredOn,
                     String description, UUID categoryId, String externalId, String message) {
    }

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    ImportRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /** Lançamentos e itens da caixa de entrada (pendentes e descartados) no período, para achar duplicatas. */
    List<Fingerprint> fingerprints(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT occurred_on, type, amount_cents, currency, description
                          FROM financial_transaction WHERE occurred_on BETWEEN :from AND :to
                        UNION ALL
                        SELECT occurred_on, type, amount_cents, currency, description
                          FROM inbox_item WHERE occurred_on BETWEEN :from AND :to
                        """)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new Fingerprint(rs.getObject("occurred_on", LocalDate.class),
                        TransactionType.valueOf(rs.getString("type")), rs.getLong("amount_cents"),
                        rs.getString("currency"), rs.getString("description")))
                .list();
    }

    void insertBatch(UUID id, ImportSource source, String fileName, int total, int fresh, int duplicates, int errors) {
        jdbc.sql("""
                        INSERT INTO import_batch (id, source, file_name, total_rows, new_rows, duplicate_rows, error_rows)
                        VALUES (:id, :source, :fileName, :total, :fresh, :duplicates, :errors)
                        """)
                .param("id", id)
                .param("source", source.name())
                .param("fileName", fileName)
                .param("total", total)
                .param("fresh", fresh)
                .param("duplicates", duplicates)
                .param("errors", errors)
                .update();
    }

    void insertRows(UUID batchId, List<NewRow> rows) {
        MapSqlParameterSource[] params = rows.stream()
                .map(r -> new MapSqlParameterSource()
                        .addValue("id", r.id())
                        .addValue("batchId", batchId)
                        .addValue("lineNo", r.lineNo())
                        .addValue("status", r.status().name())
                        .addValue("type", r.type() == null ? null : r.type().name())
                        .addValue("amountCents", r.amountCents())
                        .addValue("currency", r.currency())
                        .addValue("occurredOn", r.occurredOn())
                        .addValue("description", r.description())
                        .addValue("categoryId", r.categoryId())
                        .addValue("externalId", r.externalId())
                        .addValue("message", r.message()))
                .toArray(MapSqlParameterSource[]::new);
        named.batchUpdate("""
                INSERT INTO import_row (id, batch_id, line_no, status, type, amount_cents, currency, occurred_on,
                                        description, category_id, external_id, message)
                VALUES (:id, :batchId, :lineNo, :status, :type, :amountCents, :currency, :occurredOn,
                        :description, :categoryId, :externalId, :message)
                """, params);
    }

    Optional<ImportBatch> findBatch(UUID id) {
        return jdbc.sql("SELECT * FROM import_batch WHERE id = :id")
                .param("id", id)
                .query(ImportRepository::batch)
                .optional();
    }

    List<ImportBatch> recentBatches(int limit) {
        return jdbc.sql("SELECT * FROM import_batch ORDER BY created_at DESC LIMIT :limit")
                .param("limit", limit)
                .query(ImportRepository::batch)
                .list();
    }

    List<StoredRow> rows(UUID batchId) {
        return jdbc.sql("SELECT * FROM import_row WHERE batch_id = :batchId ORDER BY line_no, id")
                .param("batchId", batchId)
                .query((rs, n) -> {
                    String type = rs.getString("type");
                    String currency = rs.getString("currency");
                    long cents = rs.getLong("amount_cents");
                    Money amount = rs.wasNull() || currency == null ? null : Money.of(cents, currency.strip());
                    return new StoredRow(rs.getObject("id", UUID.class), rs.getInt("line_no"),
                            RowStatus.valueOf(rs.getString("status")),
                            type == null ? null : TransactionType.valueOf(type), amount,
                            rs.getObject("occurred_on", LocalDate.class), rs.getString("description"),
                            rs.getObject("category_id", UUID.class), rs.getString("external_id"), rs.getString("message"));
                })
                .list();
    }

    /** Quantas das linhas pedidas existem neste import e podem ir para a caixa de entrada. */
    int countSelectable(UUID batchId, Collection<UUID> rowIds) {
        return jdbc.sql("SELECT count(*) FROM import_row WHERE batch_id = :batchId AND status <> 'ERROR' AND id IN (:ids)")
                .param("batchId", batchId)
                .param("ids", rowIds)
                .query(Integer.class)
                .single();
    }

    /** Fecha o preview. Devolve false se outro pedido já confirmou ou cancelou (a linha fica travada até o commit). */
    boolean finish(UUID batchId, ImportStatus status, int imported) {
        return jdbc.sql("""
                        UPDATE import_batch SET status = :status, imported_rows = :imported, finished_at = now()
                         WHERE id = :id AND status = 'PREVIEW'
                        """)
                .param("id", batchId)
                .param("status", status.name())
                .param("imported", imported)
                .update() == 1;
    }

    /** Copia as linhas escolhidas para a caixa de entrada como pendentes. */
    int moveToInbox(UUID batchId, ImportSource source, Collection<UUID> rowIds) {
        return jdbc.sql("""
                        INSERT INTO inbox_item (batch_id, source, type, amount_cents, currency, occurred_on, description, category_id)
                        SELECT batch_id, :source, type, amount_cents, currency, occurred_on, description, category_id
                          FROM import_row
                         WHERE batch_id = :batchId AND status <> 'ERROR' AND id IN (:ids)
                         ORDER BY line_no
                        """)
                .param("batchId", batchId)
                .param("source", source.name())
                .param("ids", rowIds)
                .update();
    }

    private static ImportBatch batch(ResultSet rs, int n) throws SQLException {
        return new ImportBatch(rs.getObject("id", UUID.class), ImportSource.valueOf(rs.getString("source")),
                rs.getString("file_name"), ImportStatus.valueOf(rs.getString("status")), rs.getInt("total_rows"),
                rs.getInt("new_rows"), rs.getInt("duplicate_rows"), rs.getInt("error_rows"), rs.getInt("imported_rows"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("finished_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
