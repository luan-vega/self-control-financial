package com.selfcontrol.app.report;

import com.selfcontrol.domain.report.Period;
import com.selfcontrol.domain.report.Report;
import com.selfcontrol.domain.transaction.Balance;

import java.util.UUID;

/** Porta de relatórios usada pela camada web. Valores na moeda padrão do usuário logado. */
public interface ReportClient {

    /** categoryId nulo considera todas as categorias. */
    Report report(Period period, UUID categoryId);

    /** Saldo acumulado de todos os lançamentos do usuário. */
    Balance allTimeBalance();
}
