package com.selfcontrol.app.shared.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Entrada inválida detectada pela regra de negócio, ligada a um campo do formulário/JSON. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidInputException extends RuntimeException {

    private final String field;

    public InvalidInputException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
