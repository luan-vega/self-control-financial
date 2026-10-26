package com.selfcontrol.app.importing.parser;

import java.util.List;

/** Resultado do parser. currency vem do arquivo (CURDEF do OFX) ou é nula. */
public record Statement(String currency, List<StatementLine> lines) {

    public Statement {
        lines = List.copyOf(lines);
    }
}
