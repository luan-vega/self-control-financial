package com.selfcontrol.app.shared.web;

import com.selfcontrol.domain.money.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatação usada nos templates: ${@fmt.money(valor)}. */
@Component("fmt")
public class Formats {

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", PT_BR);
    private static final DateTimeFormatter SHORT_MONTH = DateTimeFormatter.ofPattern("MMM/yy", PT_BR);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM", PT_BR);
    private static final DateTimeFormatter FULL_DAY = DateTimeFormatter.ofPattern("EEEE, dd 'de' MMMM", PT_BR);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", PT_BR);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", PT_BR);
    /** Horários (imports, uso do token) são mostrados no fuso de Brasília, como o resto da app em pt-BR. */
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    public String money(Money money) {
        NumberFormat format = NumberFormat.getCurrencyInstance(PT_BR);
        format.setCurrency(money.currency());
        return format.format(money.toDecimal());
    }

    /** Valor sem símbolo, para preencher o campo do formulário ("1234,56"). */
    public String decimal(Money money) {
        DecimalFormat format = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(PT_BR));
        format.setMinimumFractionDigits(money.currency().getDefaultFractionDigits());
        format.setMaximumFractionDigits(money.currency().getDefaultFractionDigits());
        return format.format(money.toDecimal());
    }

    public String month(YearMonth month) {
        return capitalize(month.format(MONTH));
    }

    public String shortMonth(YearMonth month) {
        return capitalize(month.format(SHORT_MONTH).replace(".", ""));
    }

    public String day(LocalDate date) {
        return date.format(DAY).replace(".", "");
    }

    public String fullDay(LocalDate date) {
        return capitalize(date.format(FULL_DAY));
    }

    public String date(LocalDate date) {
        return date.format(DATE);
    }

    public String dateTime(Instant instant) {
        return instant.atZone(ZONE).format(DATE_TIME);
    }

    /** Altura/largura de barra (0 a 100) proporcional ao maior valor. */
    public int scale(Money value, Money max) {
        if (max.minorUnits() <= 0) {
            return 0;
        }
        return (int) Math.round(value.minorUnits() * 100.0 / max.minorUnits());
    }

    /**
     * Converte o valor digitado ("1.234,56", "1234,56" ou "1234.56") em decimal.
     * Lança NumberFormatException se não for um número.
     */
    public static BigDecimal parseAmount(String text) {
        String value = text.strip().replace("R$", "").replace(" ", "").replace(" ", "");
        if (value.contains(",")) {
            value = value.replace(".", "").replace(',', '.');
        }
        return new BigDecimal(value);
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
