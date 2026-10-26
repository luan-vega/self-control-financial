package com.selfcontrol.app.account;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Termos de Uso e Política de Privacidade (públicos) e o aceite para quem entrou sem passar pelo
 * cadastro (login com Google ou contas antigas). Ver {@link TermsInterceptor}.
 */
@Controller
class LegalController {

    private final AccountService accounts;

    LegalController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/legal/terms")
    String terms(Model model) {
        model.addAttribute("version", Legal.VERSION);
        return "legal/terms";
    }

    @GetMapping("/legal/privacy")
    String privacy(Model model) {
        model.addAttribute("version", Legal.VERSION);
        return "legal/privacy";
    }

    @GetMapping("/legal/accept")
    String acceptForm() {
        return "legal/accept";
    }

    @PostMapping("/legal/accept")
    String accept(@RequestParam(defaultValue = "false") boolean acceptTerms, Model model) {
        if (!acceptTerms) {
            model.addAttribute("error", "Marque a caixa para continuar");
            return "legal/accept";
        }
        accounts.acceptTerms();
        return "redirect:/";
    }
}
