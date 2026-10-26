package com.selfcontrol.app.inbox;

import com.selfcontrol.app.category.CategoryClient;
import com.selfcontrol.app.shared.error.InvalidInputException;
import com.selfcontrol.app.shared.error.PlanLimitException;
import com.selfcontrol.app.shared.web.Formats;
import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.transaction.TransactionType;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Caixa de entrada: revisar, editar, confirmar ou descartar o que veio de imports e do webhook.
 * As ações em lote devolvem a lista inteira (#inbox); as de uma linha devolvem só a linha.
 */
@Controller
@RequestMapping("/inbox")
class InboxPageController {

    private static final String LIST = "inbox/index :: inbox";
    private static final String ITEM = "inbox/index :: item";
    private static final String EDIT = "inbox/index :: edit";

    private final InboxClient inbox;
    private final CategoryClient categories;
    private final Formats fmt;

    InboxPageController(InboxClient inbox, CategoryClient categories, Formats fmt) {
        this.inbox = inbox;
        this.categories = categories;
        this.fmt = fmt;
    }

    @GetMapping
    String index(@RequestParam(required = false) Integer imported,
                 @RequestHeader(name = "HX-Request", required = false) String htmx, Model model) {
        list(model, imported == null ? null : imported == 1
                ? "1 lançamento importado. Revise e confirme abaixo."
                : imported + " lançamentos importados. Revise e confirme abaixo.");
        return htmx != null ? LIST : "inbox/index";
    }

    @PostMapping("/confirm")
    String confirm(@RequestParam(name = "ids", required = false) List<UUID> ids, Model model) {
        InboxClient.BulkResult result;
        try {
            result = inbox.confirm(ids);
        } catch (PlanLimitException e) {
            // Nada foi confirmado (tudo ou nada); os itens continuam na caixa.
            list(model, e.getMessage());
            model.addAttribute("planLimit", true);
            return LIST;
        }
        String message;
        if (result.confirmed() == 0 && result.skipped() == 0) {
            message = "Selecione os lançamentos que quer confirmar.";
        } else {
            message = plural(result.confirmed(), "lançamento confirmado", "lançamentos confirmados") + ".";
            if (result.skipped() > 0) {
                message += " " + plural(result.skipped(), "continua aqui porque está", "continuam aqui porque estão")
                        + " sem categoria.";
            }
        }
        list(model, message);
        return LIST;
    }

    @PostMapping("/discard")
    String discard(@RequestParam(name = "ids", required = false) List<UUID> ids, Model model) {
        int discarded = inbox.discard(ids);
        list(model, discarded == 0 ? "Selecione os lançamentos que quer descartar."
                : plural(discarded, "lançamento descartado", "lançamentos descartados") + ".");
        return LIST;
    }

    @GetMapping("/{id}")
    String item(@PathVariable UUID id, Model model) {
        model.addAttribute("e", inbox.get(id));
        addCategories(model);
        return ITEM;
    }

    @GetMapping("/{id}/edit")
    String edit(@PathVariable UUID id, Model model) {
        InboxEntry entry = inbox.get(id);
        return editForm(model, entry, InboxForm.of(entry, fmt));
    }

    /** Troca rápida da categoria pelo select da linha. */
    @PostMapping("/{id}/category")
    String category(@PathVariable UUID id, @RequestParam(required = false) UUID categoryId, Model model) {
        InboxEntry e = inbox.get(id);
        try {
            e = inbox.update(id, new InboxCommand(e.type(), e.amount().toDecimal(), null, e.occurredOn(),
                    categoryId, e.description()));
        } catch (InvalidInputException ex) {
            model.addAttribute("itemError", ex.getMessage());
        }
        model.addAttribute("e", e);
        addCategories(model);
        return ITEM;
    }

    @PostMapping("/{id}")
    String update(@PathVariable UUID id, @Valid @ModelAttribute("form") InboxForm form, BindingResult errors, Model model) {
        InboxEntry entry = inbox.get(id);
        BigDecimal amount = null;
        if (!errors.hasFieldErrors("amount")) {
            try {
                amount = Formats.parseAmount(form.getAmount());
            } catch (NumberFormatException e) {
                errors.rejectValue("amount", "invalid", "Valor inválido");
            }
        }
        if (!errors.hasErrors()) {
            try {
                model.addAttribute("e", inbox.update(id, new InboxCommand(form.getType(), amount, null,
                        form.getOccurredOn(), form.getCategoryId(), form.getDescription())));
                addCategories(model);
                return ITEM;
            } catch (InvalidInputException e) {
                errors.rejectValue(e.field(), "invalid", e.getMessage());
            }
        }
        return editForm(model, entry, form);
    }

    private String editForm(Model model, InboxEntry entry, InboxForm form) {
        model.addAttribute("e", entry);
        model.addAttribute("form", form);
        addCategories(model);
        return EDIT;
    }

    private void list(Model model, String message) {
        List<InboxEntry> entries = inbox.pending();
        model.addAttribute("entries", entries);
        model.addAttribute("message", message);
        model.addAttribute("hasCategories", !categories.list().isEmpty());
        addCategories(model);
    }

    private void addCategories(Model model) {
        List<Category> all = categories.list();
        model.addAttribute("expenseCategories", all.stream().filter(c -> c.type() == TransactionType.EXPENSE).toList());
        model.addAttribute("incomeCategories", all.stream().filter(c -> c.type() == TransactionType.INCOME).toList());
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
