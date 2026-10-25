package com.selfcontrol.app.category;

import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.transaction.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Campos do formulário de categoria. */
public class CategoryForm {

    @NotBlank(message = "Informe o nome")
    @Size(max = Category.MAX_NAME_LENGTH, message = "O nome deve ter até 60 caracteres")
    private String name;

    @NotNull(message = "Escolha receita ou despesa")
    private TransactionType type = TransactionType.EXPENSE;

    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Cor inválida")
    private String color = Category.DEFAULT_COLOR;

    static CategoryForm of(Category c) {
        CategoryForm form = new CategoryForm();
        form.setName(c.name());
        form.setType(c.type());
        form.setColor(c.color());
        return form;
    }

    CategoryCommand toCommand() {
        return new CategoryCommand(name, type, color);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
