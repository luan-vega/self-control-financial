package com.selfcontrol.app.transaction;

import com.selfcontrol.app.billing.BillingClient;
import com.selfcontrol.app.category.CategoryClient;
import com.selfcontrol.app.report.ReportCache;
import com.selfcontrol.app.shared.audit.AuditLog;
import com.selfcontrol.app.shared.error.InvalidInputException;
import com.selfcontrol.app.shared.error.NotFoundException;
import com.selfcontrol.app.shared.security.CurrentUser;
import com.selfcontrol.app.shared.security.UserContext;
import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.report.Period;
import com.selfcontrol.domain.transaction.Transaction;
import com.selfcontrol.domain.transaction.TransactionType;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class TransactionService implements TransactionClient {

    private final TransactionRepository transactions;
    private final CategoryClient categories;
    private final JdbcAggregateTemplate aggregates;
    private final UserContext users;
    private final BillingClient billing;
    private final ReportCache reportCache;
    private final AuditLog audit;

    TransactionService(TransactionRepository transactions, CategoryClient categories,
                       JdbcAggregateTemplate aggregates, UserContext users, BillingClient billing,
                       ReportCache reportCache, AuditLog audit) {
        this.transactions = transactions;
        this.categories = categories;
        this.aggregates = aggregates;
        this.users = users;
        this.billing = billing;
        this.reportCache = reportCache;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransactionEntry> list(Period period, UUID categoryId) {
        List<TransactionRow> rows = categoryId == null
                ? transactions.findInPeriod(period.from(), period.to())
                : transactions.findInPeriodByCategory(period.from(), period.to(), categoryId);
        Map<UUID, Category> byId = categories.list().stream()
                .collect(Collectors.toMap(Category::id, Function.identity()));
        return rows.stream()
                .map(TransactionRow::toDomain)
                .map(t -> new TransactionEntry(t, t.categoryId() == null ? null : byId.get(t.categoryId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Transaction get(UUID id) {
        return transactions.findById(id)
                .map(TransactionRow::toDomain)
                .orElseThrow(() -> new NotFoundException("Lançamento não encontrado"));
    }

    @Override
    public Transaction create(TransactionCommand command) {
        CurrentUser user = users.current();
        Transaction transaction = build(UUID.randomUUID(), user, command);
        // O limite do plano Free vale para todo lançamento novo, inclusive os confirmados da caixa de entrada.
        billing.checkCanCreateTransactions(1);
        aggregates.insert(TransactionRow.from(transaction));
        changed(transaction, "transaction.created");
        return transaction;
    }

    @Override
    public Transaction update(UUID id, TransactionCommand command) {
        get(id);
        Transaction transaction = build(id, users.current(), command);
        transactions.save(TransactionRow.from(transaction));
        changed(transaction, "transaction.updated");
        return transaction;
    }

    @Override
    public void delete(UUID id) {
        Transaction transaction = get(id);
        transactions.deleteById(id);
        changed(transaction, "transaction.deleted");
    }

    private void changed(Transaction t, String action) {
        reportCache.invalidate(t.userId());
        audit.record(action, "transaction", t.id(), Map.of("type", t.type().name(),
                "amountCents", t.amount().minorUnits(), "currency", t.amount().currency().getCurrencyCode(),
                "occurredOn", t.occurredOn().toString()));
    }

    private Transaction build(UUID id, CurrentUser user, TransactionCommand command) {
        if (command.type() == null) {
            throw new InvalidInputException("type", "Escolha receita ou despesa");
        }
        if (command.occurredOn() == null) {
            throw new InvalidInputException("occurredOn", "Informe a data");
        }
        if (command.occurredOn().isBefore(Transaction.MIN_DATE) || command.occurredOn().isAfter(Transaction.MAX_DATE)) {
            throw new InvalidInputException("occurredOn", "Data inválida");
        }
        Money amount = toMoney(command.amount(), currency(command.currency(), user));
        if (command.categoryId() == null) {
            throw new InvalidInputException("categoryId", "Escolha uma categoria");
        }
        Category category;
        try {
            category = categories.get(command.categoryId());
        } catch (NotFoundException e) {
            throw new InvalidInputException("categoryId", "Categoria não encontrada");
        }
        if (!category.accepts(command.type())) {
            throw new InvalidInputException("categoryId", "A categoria \"" + category.name() + "\" é de "
                    + (category.type() == TransactionType.INCOME ? "receitas" : "despesas"));
        }
        if (command.description() != null && command.description().strip().length() > Transaction.MAX_DESCRIPTION_LENGTH) {
            throw new InvalidInputException("description",
                    "A descrição deve ter até " + Transaction.MAX_DESCRIPTION_LENGTH + " caracteres");
        }
        return new Transaction(id, user.id(), category.id(), command.type(), amount, command.occurredOn(),
                command.description());
    }

    private static Currency currency(String code, CurrentUser user) {
        if (code == null || code.isBlank()) {
            return user.defaultCurrency();
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
}
