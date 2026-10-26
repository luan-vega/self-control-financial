package com.selfcontrol.app.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Tela de plano e cobrança: uso do mês, upgrade para o Pro e gerenciamento da assinatura. */
@Controller
@RequestMapping("/billing")
class BillingPageController {

    private final BillingClient billing;
    private final String proPrice;

    BillingPageController(BillingClient billing, @Value("${app.billing.pro-price-label:R$ 9,90/mês}") String proPrice) {
        this.billing = billing;
        this.proPrice = proPrice;
    }

    @GetMapping
    String index(Model model) {
        model.addAttribute("subscription", billing.subscription());
        model.addAttribute("usage", billing.usage());
        model.addAttribute("mock", billing.provider() == BillingProvider.MOCK);
        model.addAttribute("proPrice", proPrice);
        return "billing/index";
    }

    @PostMapping("/checkout")
    String checkout() {
        return "redirect:" + billing.startCheckout();
    }

    @PostMapping("/portal")
    String portal() {
        return "redirect:" + billing.manage();
    }

    @GetMapping("/mock-checkout")
    String mockCheckout(Model model) {
        if (billing.provider() != BillingProvider.MOCK) {
            return "redirect:/billing";
        }
        model.addAttribute("proPrice", proPrice);
        return "billing/mock-checkout";
    }

    @PostMapping("/mock-checkout")
    String confirmMock() {
        billing.confirmMockCheckout();
        return "redirect:/billing?upgraded";
    }

    @PostMapping("/mock-cancel")
    String cancelMock() {
        billing.cancelMock();
        return "redirect:/billing?downgraded";
    }
}
