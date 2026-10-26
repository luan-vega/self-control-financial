package com.selfcontrol.app.transaction;

import com.selfcontrol.app.category.CategoryClient;
import com.selfcontrol.app.shared.error.InvalidInputException;
import com.selfcontrol.app.shared.web.Formats;
import com.selfcontrol.domain.report.Period;
import com.selfcontrol.domain.transaction.Balance;
import com.selfcontrol.domain.transaction.Transaction;
import com.selfcontrol.domain.transaction.TransactionType;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Telas de lançamentos: extrato do mês, modal de novo/editar e exclusão inline (HTMX). */
@Controller
@RequestMapping("/transactions")
class TransactionPageController {

    /** Avisa a página que algo mudou; o resumo do extrato escuta este evento e se recarrega. */
    static final String CHANGED_EVENT = "transactions-changed";

    private final TransactionClient transactions;
    private final CategoryClient categories;
    private final Formats fmt;

    TransactionPageController(TransactionClient transactions, CategoryClient categories, Formats fmt) {
        this.transactions = transactions;
        this.categories = categories;
        this.fmt = fmt;
    }

    @GetMapping
    String list(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                @RequestParam(required = false) UUID categoryId,
                @RequestHeader(name = "HX-Request", required = false) String htmx,
                Model model) {
        YearMonth selected = month != null ? month : YearMonth.now();
        List<TransactionEntry> entries = transactions.list(Period.ofMonth(selected), categoryId);
        var currency = entries.isEmpty() ? null : entries.getFirst().transaction().amount().currency();
        model.addAttribute("month", selected);
        model.addAttribute("categoryId", categoryId);
        model.addAttribute("categories", categories.list());
        model.addAttribute("entries", entries);
        model.addAttribute("balance", currency == null ? null : Balance.of(entries.stream()
                .map(TransactionEntry::transaction)
                .filter(t -> t.amount().currency().equals(currency))
                .toList(), currency));
        return htmx != null ? "transactions/list :: statement" : "transactions/list";
    }

    @GetMapping("/new")
    String newForm(@RequestParam(defaultValue = "EXPENSE") TransactionType type, Model model) {
        TransactionForm form = new TransactionForm();
        form.setType(type);
        return modal(model, form, null);
    }

    @GetMapping("/{id}/edit")
    String editForm(@PathVariable UUID id, Model model) {
        Transaction t = transactions.get(id);
        TransactionForm form = new TransactionForm();
        form.setType(t.type());
        form.setAmount(fmt.decimal(t.amount()));
        form.setOccurredOn(t.occurredOn());
        form.setCategoryId(t.categoryId());
        form.setDescription(t.description());
        return modal(model, form, id);
    }

    /** Troca as opções de categoria quando o usuário alterna entre receita e despesa no modal. */
    @GetMapping("/category-options")
    String categoryOptions(@RequestParam TransactionType type, Model model) {
        TransactionForm form = new TransactionForm();
        form.setType(type);
        model.addAttribute("form", form);
        model.addAttribute("categories", categories.list(type));
        return "transactions/form :: categoryField";
    }

    @PostMapping
    String create(@Valid @ModelAttribute("form") TransactionForm form, BindingResult errors,
                  Model model, HttpServletResponse response) {
        return save(null, form, errors, model, response);
    }

    @PostMapping("/{id}")
    String update(@PathVariable UUID id, @Valid @ModelAttribute("form") TransactionForm form, BindingResult errors,
                  Model model, HttpServletResponse response) {
        return save(id, form, errors, model, response);
    }

    @DeleteMapping("/{id}")
    @ResponseBody
    String delete(@PathVariable UUID id, HttpServletResponse response) {
        transactions.delete(id);
        response.setHeader("HX-Trigger", CHANGED_EVENT);
        return "";
    }

    private String save(UUID id, TransactionForm form, BindingResult errors, Model model,
                        HttpServletResponse response) {
        BigDecimal amount = null;
        if (!errors.hasFieldErrors("amount")) {
            try {
                amount = Formats.parseAmount(form.getAmount());
            } catch (NumberFormatException e) {
                errors.rejectValue("amount", "invalid", "Valor inválido");
            }
        }
        if (!errors.hasErrors()) {
            TransactionCommand command = new TransactionCommand(form.getType(), amount, null,
                    form.getOccurredOn(), form.getCategoryId(), form.getDescription());
            try {
                if (id == null) {
                    transactions.create(command);
                } else {
                    transactions.update(id, command);
                }
                // O modal some e a página atual (início, extrato ou relatório) recarrega com os novos números.
                response.setHeader("HX-Refresh", "true");
                return "transactions/form :: saved";
            } catch (InvalidInputException e) {
                errors.rejectValue(e.field(), "invalid", e.getMessage());
            }
        }
        return modal(model, form, id);
    }

    private String modal(Model model, TransactionForm form, UUID id) {
        model.addAttribute("form", form);
        model.addAttribute("transactionId", id);
        model.addAttribute("categories", categories.list(form.getType() != null ? form.getType() : TransactionType.EXPENSE));
        return "transactions/form :: modal";
    }
}
