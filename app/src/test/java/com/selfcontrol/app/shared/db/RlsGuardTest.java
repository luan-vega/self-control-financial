package com.selfcontrol.app.shared.db;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RlsGuardTest {

    @Test
    void refusesToStartWithARoleThatBypassesRls() {
        assertThatThrownBy(() -> new RlsGuard(jdbcReturning(true), true).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BYPASSRLS");
    }

    @Test
    void canBeDisabledOrPassesWithACommonRole() {
        assertThatCode(() -> new RlsGuard(jdbcReturning(true), false).run(null)).doesNotThrowAnyException();
        assertThatCode(() -> new RlsGuard(jdbcReturning(false), true).run(null)).doesNotThrowAnyException();
    }

    private static JdbcClient jdbcReturning(boolean bypasses) {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(org.mockito.ArgumentMatchers.anyString()).query(Boolean.class).single()).thenReturn(bypasses);
        return jdbc;
    }
}
