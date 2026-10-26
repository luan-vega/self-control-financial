package com.selfcontrol.app.dashboard;

import com.selfcontrol.app.account.ProfileService;
import com.selfcontrol.app.billing.BillingClient;
import com.selfcontrol.app.inbox.InboxClient;
import com.selfcontrol.domain.billing.Usage;
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
    private final InboxClient inbox;
    private final ProfileService profiles;
    private final BillingClient billing;

    DashboardController(DashboardClient dashboard, InboxClient inbox, ProfileService profiles, BillingClient billing) {
        this.dashboard = dashboard;
        this.inbox = inbox;
        this.profiles = profiles;
        this.billing = billing;
    }

    /** Página inicial. Em requisições HTMX (troca de mês) devolve só o fragmento do resumo. */
    @GetMapping("/")
    String home(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                @RequestHeader(name = "HX-Request", required = false) String htmx,
                Model model) {
        YearMonth selected = month != null ? month : YearMonth.now();
        model.addAttribute("view", dashboard.monthSummary(selected));
        if (htmx == null) {
            model.addAttribute("inboxCount", inbox.pendingCount());
            model.addAttribute("profile", profiles.current());
            // Aviso quando o plano Free está perto do limite do mês.
            Usage usage = billing.usage();
            model.addAttribute("usage", usage.unlimited() || usage.percent() < 80 ? null : usage);
        }
        return htmx != null ? "dashboard/index :: summary" : "dashboard/index";
    }
}
