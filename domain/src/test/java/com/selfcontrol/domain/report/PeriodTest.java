package com.selfcontrol.domain.report;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodTest {

    @Test
    void monthCoversFirstToLastDay() {
        Period feb = Period.ofMonth(YearMonth.of(2028, 2));

        assertThat(feb.from()).isEqualTo(LocalDate.of(2028, 2, 1));
        assertThat(feb.to()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(feb.contains(LocalDate.of(2028, 2, 29))).isTrue();
        assertThat(feb.contains(LocalDate.of(2028, 3, 1))).isFalse();
        assertThat(feb.contains(LocalDate.of(2028, 1, 31))).isFalse();
    }

    @Test
    void listsEveryMonthTouched() {
        Period period = new Period(LocalDate.of(2026, 11, 20), LocalDate.of(2027, 1, 5));

        assertThat(period.months()).containsExactly(
                YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1));
    }

    @Test
    void rejectsInvertedOrHugePeriods() {
        assertThatThrownBy(() -> new Period(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Period(LocalDate.of(2000, 1, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
