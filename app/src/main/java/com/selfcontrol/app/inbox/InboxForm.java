package com.selfcontrol.app.inbox;

import com.selfcontrol.app.shared.web.Formats;
import com.selfcontrol.domain.transaction.Transaction;
import com.selfcontrol.domain.transaction.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.UUID;

/** Edição de um pendente na caixa de entrada. O valor chega como texto em pt-BR ("1.234,56"). */
public class InboxForm {

    @NotNull(message = "Escolha receita ou despesa")
    private TransactionType type;

    @NotBlank(message = "Informe o valor")
    private String amount;

    @NotNull(message = "Informe a data")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate occurredOn;

    private UUID categoryId;

    @Size(max = Transaction.MAX_DESCRIPTION_LENGTH, message = "A descrição deve ter até 200 caracteres")
    private String description;

    static InboxForm of(InboxEntry e, Formats fmt) {
        InboxForm form = new InboxForm();
        form.setType(e.type());
        form.setAmount(fmt.decimal(e.amount()));
        form.setOccurredOn(e.occurredOn());
        form.setCategoryId(e.category() == null ? null : e.category().id());
        form.setDescription(e.description());
        return form;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public String getAmount() {
        return amount;
    }

    public void setAmount(String amount) {
        this.amount = amount;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public void setOccurredOn(LocalDate occurredOn) {
        this.occurredOn = occurredOn;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
