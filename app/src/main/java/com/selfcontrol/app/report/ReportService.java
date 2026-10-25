package com.selfcontrol.app.report;

import com.selfcontrol.app.shared.security.UserContext;
import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.report.CategorySum;
import com.selfcontrol.domain.report.MonthlySum;
import com.selfcontrol.domain.report.Period;
import com.selfcontrol.domain.report.Report;
import com.selfcontrol.domain.transaction.Balance;
import com.selfcontrol.domain.transaction.TransactionType;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.month;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.year;

/**
 * Agregações com jOOQ (SQL tipado, sem codegen por enquanto). O RLS continua valendo: as
 * consultas rodam na transação que já tem o usuário no contexto.
 */
@Service
@Transactional(readOnly = true)
class ReportService implements ReportClient {

    private static final Table<?> TX = table(name("financial_transaction")).as("t");
    private static final Field<UUID> TX_CATEGORY = field(name("t", "category_id"), UUID.class);
    private static final Field<String> TX_TYPE = field(name("t", "type"), String.class);
    private static final Field<Long> TX_AMOUNT = field(name("t", "amount_cents"), Long.class);
    private static final Field<String> TX_CURRENCY = field(name("t", "currency"), String.class);
    private static final Field<LocalDate> TX_DATE = field(name("t", "occurred_on"), LocalDate.class);

    private static final Table<?> CAT = table(name("category")).as("c");
    private static final Field<UUID> CAT_ID = field(name("c", "id"), UUID.class);
    private static final Field<String> CAT_NAME = field(name("c", "name"), String.class);
    private static final Field<String> CAT_COLOR = field(name("c", "color"), String.class);

    private final DSLContext dsl;
    private final UserContext users;

    ReportService(DSLContext dsl, UserContext users) {
        this.dsl = dsl;
        this.users = users;
    }

    @Override
    public Report report(Period period, UUID categoryId) {
        Currency currency = users.current().defaultCurrency();
        Condition where = TX_CURRENCY.eq(currency.getCurrencyCode())
                .and(TX_DATE.between(period.from(), period.to()))
                .and(categoryId == null ? noCondition() : TX_CATEGORY.eq(categoryId));

        Field<BigDecimal> total = sum(TX_AMOUNT).as("total");

        List<CategorySum> byCategory = dsl
                .select(TX_CATEGORY, CAT_NAME, CAT_COLOR, TX_TYPE, total)
                .from(TX)
                .leftJoin(CAT).on(CAT_ID.eq(TX_CATEGORY))
                .where(where)
                .groupBy(TX_CATEGORY, CAT_NAME, CAT_COLOR, TX_TYPE)
                .fetch(r -> new CategorySum(r.value1(), r.value2(), r.value3(),
                        TransactionType.valueOf(r.value4()), money(r.value5(), currency)));

        Field<Integer> year = year(TX_DATE).as("y");
        Field<Integer> month = month(TX_DATE).as("m");
        List<MonthlySum> byMonth = dsl
                .select(year, month, TX_TYPE, total)
                .from(TX)
                .where(where)
                .groupBy(year(TX_DATE), month(TX_DATE), TX_TYPE)
                .fetch(r -> new MonthlySum(YearMonth.of(r.value1(), r.value2()),
                        TransactionType.valueOf(r.value3()), money(r.value4(), currency)));

        return Report.of(period, currency, byCategory, byMonth);
    }

    @Override
    public Balance allTimeBalance() {
        Currency currency = users.current().defaultCurrency();
        Money zero = Money.zero(currency);
        Money income = zero;
        Money expenses = zero;
        for (var r : dsl.select(TX_TYPE, sum(TX_AMOUNT))
                .from(TX)
                .where(TX_CURRENCY.eq(currency.getCurrencyCode()))
                .groupBy(TX_TYPE)
                .fetch()) {
            Money value = money(r.value2(), currency);
            if (TransactionType.valueOf(r.value1()) == TransactionType.INCOME) {
                income = value;
            } else {
                expenses = value;
            }
        }
        return new Balance(income, expenses);
    }

    private static Money money(BigDecimal cents, Currency currency) {
        return new Money(cents == null ? 0 : cents.longValueExact(), currency);
    }
}
