package com.selfcontrol.app.report;

import com.selfcontrol.app.category.CategoryClient;
import com.selfcontrol.app.transaction.TransactionClient;
import com.selfcontrol.domain.report.Period;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Relatório com filtros de período e categoria. O formulário de filtro troca só o fragmento via HTMX. */
@Controller
class ReportPageController {

    private final ReportClient reports;
    private final TransactionClient transactions;
    private final CategoryClient categories;

    ReportPageController(ReportClient reports, TransactionClient transactions, CategoryClient categories) {
        this.reports = reports;
        this.transactions = transactions;
        this.categories = categories;
    }

    @GetMapping("/reports")
    String report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                  @RequestParam(required = false) UUID categoryId,
                  @RequestHeader(name = "HX-Request", required = false) String htmx,
                  Model model) {
        YearMonth now = YearMonth.now();
        LocalDate start = from != null ? from : now.atDay(1);
        LocalDate end = to != null ? to : now.atEndOfMonth();
        String error = null;
        Period period;
        try {
            period = new Period(start, end);
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
            period = Period.ofMonth(now);
        }
        model.addAttribute("error", error);
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("categoryId", categoryId);
        model.addAttribute("categories", categories.list());
        model.addAttribute("report", reports.report(period, categoryId));
        model.addAttribute("entries", transactions.list(period, categoryId));
        model.addAttribute("presets", Presets.of(now));
        return htmx != null ? "reports/index :: report" : "reports/index";
    }

    /** Atalhos de período exibidos acima do filtro. */
    public record Presets(Preset thisMonth, Preset lastMonth, Preset last3Months, Preset thisYear) {

        public record Preset(String label, LocalDate from, LocalDate to) {
        }

        public List<Preset> all() {
            return List.of(thisMonth, lastMonth, last3Months, thisYear);
        }

        static Presets of(YearMonth now) {
            return new Presets(
                    new Preset("Este mês", now.atDay(1), now.atEndOfMonth()),
                    new Preset("Mês passado", now.minusMonths(1).atDay(1), now.minusMonths(1).atEndOfMonth()),
                    new Preset("3 meses", now.minusMonths(2).atDay(1), now.atEndOfMonth()),
                    new Preset("Este ano", now.withMonth(1).atDay(1), now.withMonth(12).atEndOfMonth()));
        }
    }
}
