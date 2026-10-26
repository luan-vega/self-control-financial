package com.selfcontrol.app.apikey;

import com.selfcontrol.app.shared.error.ConflictException;
import com.selfcontrol.app.shared.error.InvalidInputException;
import com.selfcontrol.app.shared.web.PublicUrl;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/** Tela das chaves da API pública: criar (o valor aparece uma vez), listar e revogar. */
@Controller
@RequestMapping("/settings/api-keys")
class ApiKeyPageController {

    private static final String LIST = "settings/api-keys :: keys";

    private final ApiKeyService keys;
    private final PublicUrl urls;

    ApiKeyPageController(ApiKeyService keys, PublicUrl urls) {
        this.keys = keys;
        this.urls = urls;
    }

    @GetMapping
    String index(Model model) {
        render(model, null, null);
        model.addAttribute("apiBase", urls.of("/api/v1"));
        model.addAttribute("docsUrl", urls.of("/docs"));
        return "settings/api-keys";
    }

    @PostMapping
    String create(@RequestParam(required = false) String name,
                  @RequestParam(defaultValue = "false") boolean readOnly, Model model) {
        try {
            ApiKeyService.Created created = keys.create(name, readOnly);
            render(model, created, null);
        } catch (InvalidInputException | ConflictException e) {
            render(model, null, e.getMessage());
        }
        return LIST;
    }

    @DeleteMapping("/{id}")
    String revoke(@PathVariable UUID id, Model model) {
        keys.revoke(id);
        render(model, null, null);
        return LIST;
    }

    private void render(Model model, ApiKeyService.Created created, String error) {
        model.addAttribute("keys", keys.list());
        model.addAttribute("created", created);
        model.addAttribute("error", error);
    }
}
