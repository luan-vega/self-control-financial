package com.selfcontrol.app.billing;

import com.selfcontrol.domain.billing.Usage;

import java.net.URI;

/** Porta de planos e cobrança usada pela camada web e pelos serviços. Sempre age como o usuário logado. */
public interface BillingClient {

    Subscription subscription();

    /** Lançamentos criados no mês, comparados ao limite do plano. */
    Usage usage();

    /** Lança PlanLimitException se o plano não permite criar mais {@code count} lançamentos neste mês. */
    void checkCanCreateTransactions(int count);

    BillingProvider provider();

    /** Começa o upgrade para o Pro: devolve a página de pagamento. */
    URI startCheckout();

    /** Portal para gerenciar a assinatura (ou a própria tela de plano, se ainda não há cliente no provedor). */
    URI manage();

    void confirmMockCheckout();

    void cancelMock();
}
