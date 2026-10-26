package com.selfcontrol.app.importing.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Leitura tolerante de valores e datas como aparecem em extratos de bancos. */
public final class Values {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            strict("uuuu-MM-dd"),
            strict("dd/MM/uuuu"),
            strict("d/M/uuuu"),
            strict("dd-MM-uuuu"),
            strict("dd.MM.uuuu"),
            strict("uuuuMMdd"),
            strict("dd/MM/uu"));

    /** Hora depois da data ("2026-10-10T12:00:00", "10/10/2026 08:15") é ignorada. */
    private static final Pattern TIME_SUFFIX = Pattern.compile("[T ].*$");
    private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");
    private static final Pattern THOUSANDS = Pattern.compile("\\d{1,3}(\\.\\d{3})+");

    private Values() {
    }

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }

    /** Data em qualquer um dos formatos comuns, ou null se não der para ler. */
    public static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = TIME_SUFFIX.matcher(text.strip()).replaceFirst("");
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // tenta o próximo
            }
        }
        return null;
    }

    /**
     * Valor com sinal. Aceita "1.234,56", "1234.56", "-12,34", "R$ 12,34", "(12,34)", "12,34-",
     * "12,34 D" (débito) e "12,34 C" (crédito). Com ponto e vírgula juntos, o último é o decimal.
     * Lança NumberFormatException se não for um número.
     */
    public static BigDecimal parseAmount(String text) {
        if (text == null) {
            throw new NumberFormatException("vazio");
        }
        String value = text.strip().toUpperCase(Locale.ROOT)
                .replace("R$", "").replace(" ", "").replace(" ", "");
        boolean negative = false;
        if (value.endsWith("D")) {
            negative = true;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("C")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.startsWith("(") && value.endsWith(")")) {
            negative = !negative;
            value = value.substring(1, value.length() - 1);
        }
        if (value.endsWith("-")) {
            negative = !negative;
            value = value.substring(0, value.length() - 1);
        }
        if (value.startsWith("-")) {
            negative = !negative;
            value = value.substring(1);
        } else if (value.startsWith("+")) {
            value = value.substring(1);
        }
        int comma = value.lastIndexOf(',');
        int dot = value.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            value = comma > dot
                    ? value.replace(".", "").replace(',', '.')
                    : value.replace(",", "");
        } else if (comma >= 0) {
            value = value.replace(".", "").replace(',', '.');
            if (value.indexOf('.') != value.lastIndexOf('.')) {
                throw new NumberFormatException(text);
            }
        } else if (dot >= 0 && value.indexOf('.') != dot) {
            if (!THOUSANDS.matcher(value).matches()) {
                throw new NumberFormatException(text);
            }
            value = value.replace(".", "");   // "1.234.567" só pode ser milhar
        }
        if (!NUMBER.matcher(value).matches()) {
            throw new NumberFormatException(text);
        }
        BigDecimal amount = new BigDecimal(value);
        return negative ? amount.negate() : amount;
    }
}
