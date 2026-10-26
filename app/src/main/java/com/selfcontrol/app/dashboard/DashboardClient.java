package com.selfcontrol.app.dashboard;

import java.time.YearMonth;

/**
 * Porta usada pela camada web (BFF). Hoje é atendida em processo; quando o núcleo
 * financeiro virar um serviço separado, ganha uma implementação HTTP sem mexer no controller.
 * Sempre responde pelo usuário logado.
 */
public interface DashboardClient {

    DashboardView monthSummary(YearMonth month);
}
