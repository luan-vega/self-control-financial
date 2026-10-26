package com.selfcontrol.app.billing;

import com.selfcontrol.app.shared.web.PublicUrl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Pagamento simulado (padrão): o checkout é uma tela da própria app que ativa o Pro sem cobrar.
 * Serve para desenvolvimento, testes e demonstração. Em produção use app.billing.provider=stripe.
 */
@Component
@ConditionalOnProperty(name = "app.billing.provider", havingValue = "mock", matchIfMissing = true)
class MockPaymentGateway implements PaymentGateway {

    private final PublicUrl urls;

    MockPaymentGateway(PublicUrl urls) {
        this.urls = urls;
    }

    @Override
    public BillingProvider provider() {
        return BillingProvider.MOCK;
    }

    @Override
    public URI checkout(CheckoutRequest request) {
        return URI.create(urls.of("/billing/mock-checkout"));
    }

    @Override
    public URI portal(String customerId, String returnUrl) {
        return URI.create(urls.of("/billing"));
    }
}
