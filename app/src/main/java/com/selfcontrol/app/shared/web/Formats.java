package com.selfcontrol.app.shared.web;

import com.selfcontrol.domain.money.Money;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatação usada nos templates: ${@fmt.money(valor)}. */
@Component("fmt")
public class Formats {

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", PT_BR);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM", PT_BR);

    public String money(Money money) {
        NumberFormat format = NumberFormat.getCurrencyInstance(PT_BR);
        format.setCurrency(money.currency());
        return format.format(money.toDecimal());
    }

    public String month(YearMonth month) {
        String text = month.format(MONTH);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public String day(LocalDate date) {
        return date.format(DAY).replace(".", "");
    }
}
