package com.selfcontrol.app.webhook;

import com.selfcontrol.app.category.CategoryClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Tela de configuração do Atalho do iPhone: gera/revoga o token e mostra URL, corpo e ids das categorias. */
@Controller
@RequestMapping("/settings/shortcut")
class ShortcutPageController {

    private static final String TOKEN = "settings/shortcut :: token";

    private final WebhookTokenService tokens;
    private final CategoryClient categories;

    ShortcutPageController(WebhookTokenService tokens, CategoryClient categories) {
        this.tokens = tokens;
        this.categories = categories;
    }

    @GetMapping
    String index(@RequestHeader(name = "HX-Request", required = false) String htmx, Model model) {
        render(model, null);
        model.addAttribute("webhookUrl", ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(WebhookController.PATH).toUriString());
        model.addAttribute("categories", categories.list());
        return htmx != null ? TOKEN : "settings/shortcut";
    }

    @PostMapping("/token")
    String rotate(Model model) {
        render(model, tokens.rotate());
        return TOKEN;
    }

    @DeleteMapping("/token")
    String revoke(Model model) {
        tokens.revoke();
        render(model, null);
        return TOKEN;
    }

    private void render(Model model, String newToken) {
        model.addAttribute("token", tokens.current().orElse(null));
        model.addAttribute("newToken", newToken);
    }
}
