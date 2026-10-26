package com.selfcontrol.app.importing.parser;

/** O arquivo inteiro não pôde ser lido (formato errado, vazio, sem colunas obrigatórias...). */
public class StatementFormatException extends RuntimeException {

    public StatementFormatException(String message) {
        super(message);
    }
}
