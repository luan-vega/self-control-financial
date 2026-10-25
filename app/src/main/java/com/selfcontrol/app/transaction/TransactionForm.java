package com.selfcontrol.app.transaction;

import com.selfcontrol.domain.transaction.Transaction;
import com.selfcontrol.domain.transaction.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.UUID;

/** Campos do formulário de lançamento (modal). O valor chega como texto em pt-BR ("1.234,56"). */
public class TransactionForm {

    @NotNull(message = "Escolha receita ou despesa")
    private TransactionType type = TransactionType.EXPENSE;

    @NotBlank(message = "Informe o valor")
    private String amount;

    @NotNull(message = "Informe a data")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate occurredOn = LocalDate.now();

    @NotNull(message = "Escolha uma categoria")
    private UUID categoryId;

    @Size(max = Transaction.MAX_DESCRIPTION_LENGTH, message = "A descrição deve ter até 200 caracteres")
    private String description;

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
