package com.selfcontrol.app.importing.parser;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValuesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1.234,56|1234.56",
            "1234,56|1234.56",
            "1234.56|1234.56",
            "1,234.56|1234.56",
            "-12,34|-12.34",
            "R$ 12,34|12.34",
            "-R$ 12,34|-12.34",
            "(12,34)|-12.34",
            "12,34-|-12.34",
            "12,34 D|-12.34",
            "12,34 C|12.34",
            "+7|7",
            "1.234.567|1234567",
    })
    void parsesAmounts(String text, String expected) {
        assertThat(Values.parseAmount(text)).isEqualByComparingTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1,2,3", "12..3", "--"})
    void rejectsGarbageAmounts(String text) {
        assertThatThrownBy(() -> Values.parseAmount(text)).isInstanceOf(NumberFormatException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-09", "09/10/2026", "9/10/2026", "09-10-2026", "09.10.2026", "20261009",
            "09/10/26", "2026-10-09T13:45:00-03:00", "09/10/2026 08:15"})
    void parsesDates(String text) {
        assertThat(Values.parseDate(text)).isEqualTo(LocalDate.of(2026, 10, 9));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "31/02/2026", "2026-13-01", "ontem"})
    void invalidDatesAreNull(String text) {
        assertThat(Values.parseDate(text)).isNull();
    }
}
