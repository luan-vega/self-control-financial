package com.selfcontrol.app.billing;

import java.net.URI;
import java.util.UUID;

/** Provedor de pagamento: cria a página de checkout do Pro e o portal para gerenciar a assinatura. */
interface PaymentGateway {

    record CheckoutRequest(UUID userId, String email, String customerId, String successUrl, String cancelUrl) {
    }

    BillingProvider provider();

    /** Endereço para onde o navegador vai pagar. */
    URI checkout(CheckoutRequest request);

    /** Endereço para trocar cartão, ver faturas ou cancelar. */
    URI portal(String customerId, String returnUrl);
}
