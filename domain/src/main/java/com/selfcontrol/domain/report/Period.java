package com.selfcontrol.domain.report;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Intervalo fechado de datas [from, to] usado nos relatórios. */
public record Period(LocalDate from, LocalDate to) {

    /** Evita relatórios gigantes por engano (ex.: ano digitado errado). */
    public static final int MAX_MONTHS = 120;

    public Period {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("A data final deve ser igual ou posterior à inicial");
        }
        if (YearMonth.from(from).plusMonths(MAX_MONTHS).isBefore(YearMonth.from(to))) {
            throw new IllegalArgumentException("O período pode ter no máximo " + MAX_MONTHS + " meses");
        }
    }

    public static Period ofMonth(YearMonth month) {
        return new Period(month.atDay(1), month.atEndOfMonth());
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** Meses tocados pelo período, em ordem. */
    public List<YearMonth> months() {
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            months.add(m);
        }
        return months;
    }
}
