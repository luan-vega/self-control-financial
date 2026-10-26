package com.selfcontrol.app.account;

import com.selfcontrol.app.billing.BillingClient;
import com.selfcontrol.app.shared.error.InvalidInputException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

/** Configurações da conta (hub) e o perfil: nome, moeda padrão, foto, senha e confirmação de e-mail. */
@Controller
class ProfileController {

    private final ProfileService profiles;
    private final AccountService accounts;
    private final BillingClient billing;

    ProfileController(ProfileService profiles, AccountService accounts, BillingClient billing) {
        this.profiles = profiles;
        this.accounts = accounts;
        this.billing = billing;
    }

    @GetMapping("/settings")
    String settings(Model model) {
        model.addAttribute("profile", profiles.current());
        model.addAttribute("subscription", billing.subscription());
        return "settings/index";
    }

    @GetMapping("/settings/profile")
    String profile(Model model) {
        Profile profile = profiles.current();
        render(model, profile, ProfileForm.of(profile));
        return "settings/profile";
    }

    @PostMapping("/settings/profile")
    String update(@ModelAttribute("form") ProfileForm form, BindingResult errors, Model model,
                  RedirectAttributes redirect) {
        try {
            profiles.update(form.getName(), form.getCurrency());
            redirect.addFlashAttribute("message", "Perfil salvo.");
            return "redirect:/settings/profile";
        } catch (InvalidInputException e) {
            errors.rejectValue(e.field(), "invalid", e.getMessage());
        }
        render(model, profiles.current(), form);
        return "settings/profile";
    }

    @PostMapping("/settings/profile/avatar")
    String uploadAvatar(@RequestParam("avatar") MultipartFile file, RedirectAttributes redirect) throws IOException {
        try {
            profiles.setAvatar(file.getBytes());
            redirect.addFlashAttribute("message", "Foto atualizada.");
        } catch (InvalidInputException e) {
            redirect.addFlashAttribute("avatarError", e.getMessage());
        }
        return "redirect:/settings/profile";
    }

    @PostMapping("/settings/profile/avatar/delete")
    String removeAvatar(RedirectAttributes redirect) {
        profiles.removeAvatar();
        redirect.addFlashAttribute("message", "Foto removida.");
        return "redirect:/settings/profile";
    }

    /** Foto da conta logada. Privada (só o dono vê), por isso sem cache compartilhado. */
    @GetMapping("/settings/profile/avatar")
    ResponseEntity<byte[]> avatar() {
        return profiles.avatar()
                .map(a -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(a.contentType()))
                        .cacheControl(CacheControl.noCache().cachePrivate())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(a.bytes()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/settings/profile/verification")
    String resend(RedirectAttributes redirect) {
        accounts.resendVerification();
        redirect.addFlashAttribute("message", "Enviamos um novo link de confirmação para o seu e-mail.");
        return "redirect:/settings/profile";
    }

    @PostMapping("/settings/password")
    String changePassword(@RequestParam(required = false) String currentPassword,
                          @RequestParam(required = false) String newPassword, RedirectAttributes redirect) {
        try {
            accounts.changePassword(currentPassword, newPassword);
            redirect.addFlashAttribute("message", "Senha alterada.");
        } catch (InvalidInputException e) {
            redirect.addFlashAttribute("passwordError", e.getMessage());
        }
        return "redirect:/settings/profile";
    }

    private void render(Model model, Profile profile, ProfileForm form) {
        model.addAttribute("profile", profile);
        model.addAttribute("form", form);
        model.addAttribute("currencies", SupportedCurrencies.CODES);
    }
}
