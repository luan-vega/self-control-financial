package com.selfcontrol.domain.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void parsesDecimalIntoMinorUnits() {
        assertThat(Money.parse("12.34", "BRL")).isEqualTo(Money.of(1234, "BRL"));
        assertThat(Money.parse("1000", "USD").minorUnits()).isEqualTo(100_000);
    }

    @Test
    void rejectsMoreDecimalsThanTheCurrencyAllows() {
        assertThatThrownBy(() -> Money.parse("1.234", "BRL")).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void respectsCurrencyFractionDigits() {
        assertThat(Money.parse("500", "JPY").minorUnits()).isEqualTo(500);
        assertThat(Money.of(1999, "BRL").toDecimal()).isEqualByComparingTo(new BigDecimal("19.99"));
    }

    @Test
    void addsAndSubtractsInTheSameCurrency() {
        Money a = Money.of(1000, "BRL");
        Money b = Money.of(250, "BRL");

        assertThat(a.plus(b)).isEqualTo(Money.of(1250, "BRL"));
        assertThat(b.minus(a)).isEqualTo(Money.of(-750, "BRL"));
        assertThat(b.minus(a).isNegative()).isTrue();
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.of(1, "BRL").plus(Money.of(1, "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void failsLoudlyOnOverflow() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "BRL").plus(Money.of(1, "BRL")))
                .isInstanceOf(ArithmeticException.class);
    }
}
