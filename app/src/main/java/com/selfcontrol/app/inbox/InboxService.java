package com.selfcontrol.app.inbox;

import com.selfcontrol.app.category.CategoryClient;
import com.selfcontrol.app.importing.CategoryMatcher;
import com.selfcontrol.app.importing.CategoryMatching;
import com.selfcontrol.app.importing.ImportSource;
import com.selfcontrol.app.shared.error.InvalidInputException;
import com.selfcontrol.app.shared.error.NotFoundException;
import com.selfcontrol.app.shared.security.UserContext;
import com.selfcontrol.app.transaction.TransactionClient;
import com.selfcontrol.app.transaction.TransactionCommand;
import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.importing.DuplicateDetector;
import com.selfcontrol.domain.importing.Fingerprint;
import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.transaction.Transaction;
import com.selfcontrol.domain.transaction.TransactionType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class InboxService implements InboxClient {

    private record Row(UUID id, ImportSource source, String fileName, TransactionType type, Money amount,
                       LocalDate occurredOn, String description, UUID categoryId) {
    }

    private static final String SELECT_PENDING = """
            SELECT i.id, i.source, b.file_name, i.type, i.amount_cents, i.currency, i.occurred_on, i.description, i.category_id
              FROM inbox_item i
              LEFT JOIN import_batch b ON b.id = i.batch_id
             WHERE i.status = 'PENDING'
            """;

    private final JdbcClient jdbc;
    private final CategoryClient categories;
    private final CategoryMatching matching;
    private final TransactionClient transactions;
    private final UserContext users;

    InboxService(JdbcClient jdbc, CategoryClient categories, CategoryMatching matching,
                 TransactionClient transactions, UserContext users) {
        this.jdbc = jdbc;
        this.categories = categories;
        this.matching = matching;
        this.transactions = transactions;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InboxEntry> pending() {
        List<Row> rows = jdbc.sql(SELECT_PENDING + " ORDER BY i.occurred_on DESC, i.created_at DESC, i.id")
                .query(InboxService::row)
                .list();
        return entries(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public int pendingCount() {
        return jdbc.sql("SELECT count(*) FROM inbox_item WHERE status = 'PENDING'").query(Integer.class).single();
    }

    @Override
    @Transactional(readOnly = true)
    public InboxEntry get(UUID id) {
        return entries(List.of(find(id))).getFirst();
    }

    @Override
    public InboxEntry add(InboxCommand command, ImportSource source) {
        CategoryMatcher matcher = matching.load();
        Currency currency = currency(command.currency());
        Money amount = toMoney(command.amount(), currency);
        LocalDate date = date(command.occurredOn());
        String description = description(command.description());
        TransactionType type;
        UUID categoryId;
        if (command.categoryId() != null) {
            Category category = category(command.categoryId(), command.type());
            type = command.type() != null ? command.type() : category.type();
            categoryId = category.id();
        } else {
            CategoryMatcher.Match match = matcher.resolve(null, command.type(), description);
            type = match.type();
            categoryId = match.category() == null ? null : match.category().id();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO inbox_item (id, source, type, amount_cents, currency, occurred_on, description, category_id)
                        VALUES (:id, :source, :type, :cents, :currency, :date, :description, :categoryId)
                        """)
                .param("id", id)
                .param("source", source.name())
                .param("type", type.name())
                .param("cents", amount.minorUnits())
                .param("currency", currency.getCurrencyCode())
                .param("date", date)
                .param("description", description)
                .param("categoryId", categoryId)
                .update();
        return get(id);
    }

    @Override
    public InboxEntry update(UUID id, InboxCommand command) {
        Row current = find(id);
        if (command.type() == null) {
            throw new InvalidInputException("type", "Escolha receita ou despesa");
        }
        Money amount = toMoney(command.amount(), current.amount().currency());
        LocalDate date = date(command.occurredOn());
        String description = description(command.description());
        UUID categoryId = command.categoryId() == null ? null : category(command.categoryId(), command.type()).id();
        jdbc.sql("""
                        UPDATE inbox_item
                           SET type = :type, amount_cents = :cents, occurred_on = :date, description = :description,
                               category_id = :categoryId, updated_at = now()
                         WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .param("type", command.type().name())
                .param("cents", amount.minorUnits())
                .param("date", date)
                .param("description", description)
                .param("categoryId", categoryId)
                .update();
        return get(id);
    }

    @Override
    public BulkResult confirm(Collection<UUID> ids) {
        Set<UUID> wanted = ids(ids);
        if (wanted.isEmpty()) {
            return new BulkResult(0, 0);
        }
        Map<UUID, Category> byId = categoriesById();
        List<Row> rows = jdbc.sql(SELECT_PENDING + " AND i.id IN (:ids) ORDER BY i.occurred_on, i.created_at")
                .param("ids", wanted)
                .query(InboxService::row)
                .list();
        int confirmed = 0;
        for (Row r : rows) {
            Category category = r.categoryId() == null ? null : byId.get(r.categoryId());
            // Validado aqui: uma exceção dentro de TransactionClient marcaria a transação inteira para rollback.
            if (category == null || !category.accepts(r.type())) {
                continue;
            }
            transactions.create(new TransactionCommand(r.type(), r.amount().toDecimal(),
                    r.amount().currency().getCurrencyCode(), r.occurredOn(), category.id(), r.description()));
            jdbc.sql("DELETE FROM inbox_item WHERE id = :id").param("id", r.id()).update();
            confirmed++;
        }
        return new BulkResult(confirmed, rows.size() - confirmed);
    }

    @Override
    public int discard(Collection<UUID> ids) {
        Set<UUID> wanted = ids(ids);
        if (wanted.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE inbox_item SET status = 'DISCARDED', updated_at = now()
                         WHERE status = 'PENDING' AND id IN (:ids)
                        """)
                .param("ids", wanted)
                .update();
    }

    private Row find(UUID id) {
        return jdbc.sql(SELECT_PENDING + " AND i.id = :id")
                .param("id", id)
                .query(InboxService::row)
                .optional()
                .orElseThrow(() -> new NotFoundException("Item não encontrado na caixa de entrada"));
    }

    /** Resolve categorias e marca possíveis duplicatas contra os lançamentos já confirmados. */
    private List<InboxEntry> entries(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Category> byId = categoriesById();
        LocalDate from = rows.stream().map(Row::occurredOn).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = rows.stream().map(Row::occurredOn).max(Comparator.naturalOrder()).orElseThrow();
        List<Fingerprint> confirmed = jdbc.sql("""
                        SELECT occurred_on, type, amount_cents, currency, description
                          FROM financial_transaction WHERE occurred_on BETWEEN :from AND :to
                        """)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new Fingerprint(rs.getObject("occurred_on", LocalDate.class),
                        TransactionType.valueOf(rs.getString("type")), rs.getLong("amount_cents"),
                        rs.getString("currency"), rs.getString("description")))
                .list();
        DuplicateDetector detector = new DuplicateDetector(confirmed);
        return rows.stream()
                .map(r -> new InboxEntry(r.id(), r.source(), r.fileName(), r.type(), r.amount(), r.occurredOn(),
                        r.description(), r.categoryId() == null ? null : byId.get(r.categoryId()),
                        detector.isDuplicate(new Fingerprint(r.occurredOn(), r.type(), r.amount().minorUnits(),
                                r.amount().currency().getCurrencyCode(), r.description()))))
                .toList();
    }

    private Map<UUID, Category> categoriesById() {
        return categories.list().stream().collect(Collectors.toMap(Category::id, Function.identity()));
    }

    private Category category(UUID id, TransactionType type) {
        Category category;
        try {
            category = categories.get(id);
        } catch (NotFoundException e) {
            throw new InvalidInputException("categoryId", "Categoria não encontrada");
        }
        if (type != null && !category.accepts(type)) {
            throw new InvalidInputException("categoryId", "A categoria \"" + category.name() + "\" é de "
                    + (category.type() == TransactionType.INCOME ? "receitas" : "despesas"));
        }
        return category;
    }

    private Currency currency(String code) {
        if (code == null || code.isBlank()) {
            return users.current().defaultCurrency();
        }
        try {
            return Currency.getInstance(code.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("currency", "Moeda desconhecida: " + code);
        }
    }

    private static Money toMoney(BigDecimal amount, Currency currency) {
        if (amount == null) {
            throw new InvalidInputException("amount", "Informe o valor");
        }
        if (amount.signum() <= 0) {
            throw new InvalidInputException("amount", "O valor deve ser maior que zero");
        }
        try {
            BigDecimal scaled = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
            return new Money(scaled.unscaledValue().longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new InvalidInputException("amount",
                    "Valor inválido: use até " + currency.getDefaultFractionDigits() + " casas decimais");
        }
    }

    private static LocalDate date(LocalDate date) {
        if (date == null) {
            throw new InvalidInputException("occurredOn", "Informe a data");
        }
        if (date.isBefore(Transaction.MIN_DATE) || date.isAfter(Transaction.MAX_DATE)) {
            throw new InvalidInputException("occurredOn", "Data inválida");
        }
        return date;
    }

    private static String description(String text) {
        String description = text == null ? "" : text.strip();
        if (description.length() > Transaction.MAX_DESCRIPTION_LENGTH) {
            throw new InvalidInputException("description",
                    "A descrição deve ter até " + Transaction.MAX_DESCRIPTION_LENGTH + " caracteres");
        }
        return description;
    }

    private static Set<UUID> ids(Collection<UUID> ids) {
        Set<UUID> set = new LinkedHashSet<>(ids == null ? List.of() : ids);
        set.remove(null);
        return set;
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), ImportSource.valueOf(rs.getString("source")),
                rs.getString("file_name"), TransactionType.valueOf(rs.getString("type")),
                Money.of(rs.getLong("amount_cents"), rs.getString("currency").strip()),
                rs.getObject("occurred_on", LocalDate.class), rs.getString("description"),
                rs.getObject("category_id", UUID.class));
    }
}
