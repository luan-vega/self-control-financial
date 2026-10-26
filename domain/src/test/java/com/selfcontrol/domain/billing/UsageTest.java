package com.selfcontrol.domain.billing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageTest {

    @Test
    void freePlanHasAMonthlyLimit() {
        Usage usage = Plan.FREE.usage(98);

        assertThat(usage.limit()).hasValue(100);
        assertThat(usage.allows(1)).isTrue();
        assertThat(usage.allows(2)).isTrue();
        assertThat(usage.allows(3)).isFalse();
        assertThat(usage.remaining()).isEqualTo(2);
        assertThat(usage.percent()).isEqualTo(98);
        assertThat(usage.unlimited()).isFalse();
    }

    @Test
    void usageAboveTheLimitIsCapped() {
        Usage usage = Plan.FREE.usage(130);

        assertThat(usage.remaining()).isZero();
        assertThat(usage.percent()).isEqualTo(100);
        assertThat(usage.allows(0)).isFalse();
    }

    @Test
    void proIsUnlimited() {
        Usage usage = Plan.PRO.usage(10_000);

        assertThat(usage.unlimited()).isTrue();
        assertThat(usage.allows(1_000)).isTrue();
        assertThat(usage.remaining()).isEqualTo(Integer.MAX_VALUE);
        assertThat(usage.percent()).isZero();
        assertThat(Plan.PRO.label()).isEqualTo("Pro");
    }

    @Test
    void rejectsNegativeUsage() {
        assertThatThrownBy(() -> Plan.FREE.usage(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(null, 1)).isInstanceOf(NullPointerException.class);
    }
}
