package com.selfcontrol.app.importing.parser;

import com.selfcontrol.domain.transaction.Transaction;
import com.selfcontrol.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê extratos OFX 1.x (SGML, com tags de valor sem fechamento) e 2.x (XML). Não usa parser
 * de XML de propósito: o SGML do OFX 1.x não é XML válido e é o que a maioria dos bancos
 * brasileiros exporta. Cada {@code <STMTTRN>} vira uma linha; o sinal de TRNAMT define o tipo.
 */
final class OfxParser {

    private static final Pattern TRANSACTION = Pattern.compile("<STMTTRN>(.*?)</STMTTRN>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CURRENCY = Pattern.compile("<CURDEF>\\s*([A-Za-z]{3})", Pattern.CASE_INSENSITIVE);

    private OfxParser() {
    }

    static Statement parse(String content) {
        String upper = content.toUpperCase(Locale.ROOT);
        if (!upper.contains("<OFX>")) {
            throw new StatementFormatException("O arquivo não parece ser um OFX (falta a tag <OFX>)");
        }
        Matcher currency = CURRENCY.matcher(content);
        String currencyCode = currency.find() ? currency.group(1).toUpperCase(Locale.ROOT) : null;

        List<StatementLine> lines = new ArrayList<>();
        Matcher m = TRANSACTION.matcher(content);
        int n = 0;
        while (m.find()) {
            n++;
            lines.add(line(n, m.group(1)));
        }
        if (lines.isEmpty()) {
            throw new StatementFormatException("O OFX não tem nenhuma transação (<STMTTRN>)");
        }
        return new Statement(currencyCode, lines);
    }

    private static StatementLine line(int n, String block) {
        String name = element(block, "NAME");
        String memo = element(block, "MEMO");
        String description = describe(name, memo);
        String fitId = element(block, "FITID");

        String posted = element(block, "DTPOSTED");
        LocalDate date = posted.length() >= 8 ? Values.parseDate(posted.substring(0, 8)) : null;
        if (date == null) {
            return StatementLine.invalid(n, description, "Data inválida: \"" + posted + "\"");
        }
        String rawAmount = element(block, "TRNAMT");
        BigDecimal amount;
        try {
            amount = Values.parseAmount(rawAmount);
        } catch (NumberFormatException e) {
            return StatementLine.invalid(n, description, "Valor inválido: \"" + rawAmount + "\"");
        }
        if (amount.signum() == 0) {
            return StatementLine.invalid(n, description, "O valor deve ser diferente de zero");
        }
        TransactionType type = amount.signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME;
        return new StatementLine(n, date, description, amount.abs(), type, null, fitId.isEmpty() ? null : fitId, null);
    }

    /** Valor de uma tag: até o próximo "<" (SGML) ou a tag de fechamento (XML). */
    private static String element(String block, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)", Pattern.CASE_INSENSITIVE).matcher(block);
        return m.find() ? unescape(m.group(1).strip()) : "";
    }

    /** Muitos bancos põem o tipo em NAME ("PIX ENVIADO") e o favorecido em MEMO. */
    private static String describe(String name, String memo) {
        String description;
        if (name.isEmpty()) {
            description = memo;
        } else if (memo.isEmpty() || memo.equalsIgnoreCase(name)) {
            description = name;
        } else {
            description = name + " - " + memo;
        }
        description = description.replaceAll("\\s+", " ").strip();
        return description.length() > Transaction.MAX_DESCRIPTION_LENGTH
                ? description.substring(0, Transaction.MAX_DESCRIPTION_LENGTH).strip()
                : description;
    }

    private static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
    }
}
