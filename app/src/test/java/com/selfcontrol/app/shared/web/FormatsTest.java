package com.selfcontrol.app.shared.web;

import com.selfcontrol.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormatsTest {

    private final Formats fmt = new Formats();

    @Test
    void parsesBrazilianAndPlainAmounts() {
        assertThat(Formats.parseAmount("1.234,56")).isEqualByComparingTo("1234.56");
        assertThat(Formats.parseAmount("R$ 12,5")).isEqualByComparingTo("12.5");
        assertThat(Formats.parseAmount("99.90")).isEqualByComparingTo(new BigDecimal("99.90"));
        assertThatThrownBy(() -> Formats.parseAmount("doze")).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void formatsForTheScreen() {
        assertThat(fmt.money(Money.of(123_456, "BRL"))).isEqualTo("R$ 1.234,56");
        assertThat(fmt.decimal(Money.of(4_990, "BRL"))).isEqualTo("49,90");
        assertThat(fmt.month(YearMonth.of(2026, 10))).isEqualTo("Outubro de 2026");
        assertThat(fmt.shortMonth(YearMonth.of(2026, 9))).isEqualTo("Set/26");
        assertThat(fmt.day(LocalDate.of(2026, 10, 1))).isEqualTo("01 out");
        assertThat(fmt.date(LocalDate.of(2026, 10, 1))).isEqualTo("01/10/2026");
        assertThat(fmt.fullDay(LocalDate.of(2026, 10, 12))).isEqualTo("Segunda-feira, 12 de outubro");
    }

    @Test
    void scalesBars() {
        assertThat(fmt.scale(Money.of(50, "BRL"), Money.of(200, "BRL"))).isEqualTo(25);
        assertThat(fmt.scale(Money.of(50, "BRL"), Money.of(0, "BRL"))).isZero();
    }
}
