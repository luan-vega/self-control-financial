package com.selfcontrol.app.dashboard;

import java.time.YearMonth;

/**
 * Porta usada pela camada web (BFF). Hoje é atendida em processo; quando o núcleo
 * financeiro virar um serviço separado, ganha uma implementação HTTP sem mexer no controller.
 */
public interface DashboardClient {

    DashboardView monthSummary(String username, YearMonth month);
}
