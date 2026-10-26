package com.selfcontrol.app.dashboard;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.YearMonth;

@Controller
class DashboardController {

    private final DashboardClient dashboard;

    DashboardController(DashboardClient dashboard) {
        this.dashboard = dashboard;
    }

    /** Página inicial. Em requisições HTMX (troca de mês) devolve só o fragmento do resumo. */
    @GetMapping("/")
    String home(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                @RequestHeader(name = "HX-Request", required = false) String htmx,
                Model model) {
        YearMonth selected = month != null ? month : YearMonth.now();
        model.addAttribute("view", dashboard.monthSummary(selected));
        return htmx != null ? "dashboard/index :: summary" : "dashboard/index";
    }
}
