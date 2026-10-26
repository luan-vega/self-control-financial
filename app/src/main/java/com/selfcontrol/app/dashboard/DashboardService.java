package com.selfcontrol.app.dashboard;

import com.selfcontrol.app.category.CategoryClient;
import com.selfcontrol.app.report.ReportClient;
import com.selfcontrol.app.shared.web.Donut;
import com.selfcontrol.app.transaction.TransactionClient;
import com.selfcontrol.domain.report.Period;
import com.selfcontrol.domain.report.Report;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

@Service
@Transactional(readOnly = true)
class DashboardService implements DashboardClient {

    static final int RECENT = 5;

    private final ReportClient reports;
    private final TransactionClient transactions;
    private final CategoryClient categories;

    DashboardService(ReportClient reports, TransactionClient transactions, CategoryClient categories) {
        this.reports = reports;
        this.transactions = transactions;
        this.categories = categories;
    }

    @Override
    public DashboardView monthSummary(YearMonth month) {
        Period period = Period.ofMonth(month);
        Report report = reports.report(period, null);
        return new DashboardView(
                month,
                reports.allTimeBalance(),
                report.balance(),
                Donut.of(report.expensesByCategory()),
                transactions.list(period, null).stream().limit(RECENT).toList(),
                !categories.list().isEmpty());
    }
}
