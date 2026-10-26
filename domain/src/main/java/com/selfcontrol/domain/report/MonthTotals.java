package com.selfcontrol.domain.report;

import com.selfcontrol.domain.transaction.Balance;

import java.time.YearMonth;

public record MonthTotals(YearMonth month, Balance balance) {
}
