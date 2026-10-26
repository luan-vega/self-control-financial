package com.selfcontrol.app.category;

import com.selfcontrol.app.shared.error.ConflictException;
import com.selfcontrol.app.shared.error.InvalidInputException;
import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.transaction.TransactionType;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.UUID;

/**
 * Tela de categorias. Toda ação HTMX devolve o fragmento da lista inteira já atualizado,
 * com o formulário de edição aberto na linha certa e eventuais mensagens de erro.
 */
@Controller
@RequestMapping("/categories")
class CategoryPageController {

    record Group(String label, List<Category> categories) {
    }

    private final CategoryClient categories;

    CategoryPageController(CategoryClient categories) {
        this.categories = categories;
    }

    @GetMapping
    String index(@RequestHeader(name = "HX-Request", required = false) String htmx, Model model) {
        render(model, new CategoryForm(), null, null, null);
        return htmx != null ? "categories/index :: list" : "categories/index";
    }

    @PostMapping
    String create(@Valid @ModelAttribute("form") CategoryForm form, BindingResult errors, Model model) {
        if (!errors.hasErrors()) {
            try {
                categories.create(form.toCommand());
                CategoryForm fresh = new CategoryForm();
                fresh.setType(form.getType());
                render(model, fresh, null, null, null);
                return "categories/index :: list";
            } catch (InvalidInputException e) {
                errors.rejectValue(e.field(), "invalid", e.getMessage());
            } catch (ConflictException e) {
                errors.rejectValue("name", "conflict", e.getMessage());
            }
        }
        render(model, form, null, null, null);
        return "categories/index :: list";
    }

    @GetMapping("/{id}/edit")
    String edit(@PathVariable UUID id, Model model) {
        render(model, new CategoryForm(), id, CategoryForm.of(categories.get(id)), null);
        return "categories/index :: list";
    }

    @PostMapping("/{id}")
    String update(@PathVariable UUID id, @Valid @ModelAttribute("editForm") CategoryForm form, BindingResult errors,
                  Model model) {
        if (!errors.hasErrors()) {
            try {
                categories.update(id, form.toCommand());
                render(model, new CategoryForm(), null, null, null);
                return "categories/index :: list";
            } catch (InvalidInputException e) {
                errors.rejectValue(e.field(), "invalid", e.getMessage());
            } catch (ConflictException e) {
                errors.reject("conflict", e.getMessage());
            }
        }
        render(model, new CategoryForm(), id, form, null);
        return "categories/index :: list";
    }

    @DeleteMapping("/{id}")
    String delete(@PathVariable UUID id, Model model) {
        String error = null;
        try {
            categories.delete(id);
        } catch (ConflictException e) {
            error = e.getMessage();
        }
        render(model, new CategoryForm(), null, null, error);
        return "categories/index :: list";
    }

    @PostMapping("/defaults")
    String createDefaults(Model model) {
        categories.createDefaultsIfEmpty();
        render(model, new CategoryForm(), null, null, null);
        return "categories/index :: list";
    }

    private void render(Model model, CategoryForm form, UUID editingId, CategoryForm editForm, String error) {
        List<Category> all = categories.list();
        model.addAttribute("groups", List.of(
                new Group("Despesas", all.stream().filter(c -> c.type() == TransactionType.EXPENSE).toList()),
                new Group("Receitas", all.stream().filter(c -> c.type() == TransactionType.INCOME).toList())));
        model.addAttribute("empty", all.isEmpty());
        model.addAttribute("form", form);
        model.addAttribute("editingId", editingId);
        if (editForm != null) {
            model.addAttribute("editForm", editForm);
        }
        model.addAttribute("error", error);
    }
}
