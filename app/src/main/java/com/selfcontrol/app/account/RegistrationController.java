package com.selfcontrol.app.account;

import com.selfcontrol.app.shared.error.InvalidInputException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Telas públicas da conta: cadastro, confirmação de e-mail e recuperação de senha. */
@Controller
class RegistrationController {

    private final AccountService accounts;

    RegistrationController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/register")
    String form(Model model) {
        model.addAttribute("form", new RegistrationForm());
        model.addAttribute("currencies", SupportedCurrencies.CODES);
        return "account/register";
    }

    @PostMapping("/register")
    String register(@ModelAttribute("form") RegistrationForm form, BindingResult errors, Model model) {
        try {
            accounts.register(form.toRegistration());
            return "redirect:/login?registered";
        } catch (InvalidInputException e) {
            errors.rejectValue(e.field(), "invalid", e.getMessage());
        }
        form.setPassword(null);
        model.addAttribute("currencies", SupportedCurrencies.CODES);
        return "account/register";
    }

    @GetMapping("/verify-email")
    String verify(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("verified", accounts.verifyEmail(token));
        return "account/verified";
    }

    @GetMapping("/forgot-password")
    String forgot() {
        return "account/forgot-password";
    }

    @PostMapping("/forgot-password")
    String requestReset(@RequestParam(required = false) String email, Model model) {
        accounts.requestPasswordReset(email);
        model.addAttribute("sent", true);
        return "account/forgot-password";
    }

    @GetMapping("/reset-password")
    String resetForm(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("token", token);
        model.addAttribute("valid", accounts.resetTokenValid(token));
        return "account/reset-password";
    }

    @PostMapping("/reset-password")
    String reset(@RequestParam(required = false) String token, @RequestParam(required = false) String password,
                 Model model) {
        try {
            if (accounts.resetPassword(token, password)) {
                return "redirect:/login?reset";
            }
            model.addAttribute("valid", false);
        } catch (InvalidInputException e) {
            model.addAttribute("valid", true);
            model.addAttribute("error", e.getMessage());
        }
        model.addAttribute("token", token);
        return "account/reset-password";
    }
}
