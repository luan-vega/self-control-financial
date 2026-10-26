package com.selfcontrol.app.importing.parser;

import java.util.Locale;

/** Formatos aceitos. Detecta pela extensão e, sem extensão conhecida, pelo conteúdo. */
public enum StatementFormat {
    OFX, CSV;

    public static StatementFormat detect(String fileName, String content) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".ofx") || name.endsWith(".qfx")) {
            return OFX;
        }
        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            return CSV;
        }
        String head = content.substring(0, Math.min(content.length(), 2048)).toUpperCase(Locale.ROOT);
        return head.contains("OFXHEADER") || head.contains("<OFX>") ? OFX : CSV;
    }

    public Statement parse(String content) {
        return switch (this) {
            case OFX -> OfxParser.parse(content);
            case CSV -> CsvParser.parse(content);
        };
    }
}
