package com.selfcontrol.app.report;

import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.report.CategoryShare;
import com.selfcontrol.domain.report.Period;
import com.selfcontrol.domain.report.Report;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * API JSON do relatório. Valores em centavos (inteiros) na moeda padrão do usuário. Também
 * publicada em /api/v1/reports (API pública, com chave de API).
 */
@RestController
@RequestMapping({"/api/reports", "/api/v1/reports"})
class ReportApiController {

    record Totals(long incomeCents, long expensesCents, long netCents) {

        static Totals of(Money income, Money expenses) {
            return new Totals(income.minorUnits(), expenses.minorUnits(), income.minus(expenses).minorUnits());
        }
    }

    record CategoryLine(UUID categoryId, String name, String color, long totalCents, int percent) {

        static CategoryLine of(CategoryShare s) {
            return new CategoryLine(s.categoryId(), s.name(), s.color(), s.total().minorUnits(), s.percent());
        }
    }

    record MonthLine(YearMonth month, Totals totals) {
    }

    record ReportResponse(LocalDate from, LocalDate to, String currency, Totals totals,
                          List<CategoryLine> expensesByCategory, List<CategoryLine> incomeByCategory,
                          List<MonthLine> months) {

        static ReportResponse of(Report r) {
            return new ReportResponse(r.period().from(), r.period().to(), r.currency().getCurrencyCode(),
                    Totals.of(r.balance().income(), r.balance().expenses()),
                    r.expensesByCategory().stream().map(CategoryLine::of).toList(),
                    r.incomeByCategory().stream().map(CategoryLine::of).toList(),
                    r.months().stream()
                            .map(m -> new MonthLine(m.month(), Totals.of(m.balance().income(), m.balance().expenses())))
                            .toList());
        }
    }

    private final ReportClient reports;

    ReportApiController(ReportClient reports) {
        this.reports = reports;
    }

    /** Sem período, usa o mês atual. */
    @GetMapping
    ReportResponse report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(required = false) UUID categoryId) {
        YearMonth now = YearMonth.now();
        Period period = new Period(from != null ? from : now.atDay(1), to != null ? to : now.atEndOfMonth());
        return ReportResponse.of(reports.report(period, categoryId));
    }
}
