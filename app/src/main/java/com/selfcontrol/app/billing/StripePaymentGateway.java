package com.selfcontrol.app.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;

/**
 * Stripe de verdade, pela API REST (sem SDK): Checkout para assinar o Pro e o Customer Portal para
 * gerenciar. A confirmação do pagamento chega depois, pelo webhook ({@link StripeWebhookController}).
 * Chaves só por variável de ambiente: STRIPE_SECRET_KEY, STRIPE_PRICE_ID e STRIPE_WEBHOOK_SECRET.
 */
@Component
@ConditionalOnProperty(name = "app.billing.provider", havingValue = "stripe")
class StripePaymentGateway implements PaymentGateway {

    static final String API = "https://api.stripe.com";

    private final RestClient http;
    private final String priceId;

    StripePaymentGateway(RestClient.Builder builder,
                         @Value("${app.billing.stripe.secret-key}") String secretKey,
                         @Value("${app.billing.stripe.price-id}") String priceId) {
        if (secretKey.isBlank() || priceId.isBlank()) {
            throw new IllegalStateException("Defina STRIPE_SECRET_KEY e STRIPE_PRICE_ID para usar app.billing.provider=stripe");
        }
        this.http = builder.baseUrl(API)
                .defaultHeader("Authorization", "Bearer " + secretKey)
                .build();
        this.priceId = priceId;
    }

    @Override
    public BillingProvider provider() {
        return BillingProvider.STRIPE;
    }

    @Override
    public URI checkout(CheckoutRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "subscription");
        form.add("line_items[0][price]", priceId);
        form.add("line_items[0][quantity]", "1");
        form.add("success_url", request.successUrl());
        form.add("cancel_url", request.cancelUrl());
        // Liga o pagamento à conta: o webhook usa estes campos para saber quem virou Pro.
        form.add("client_reference_id", request.userId().toString());
        form.add("metadata[user_id]", request.userId().toString());
        form.add("subscription_data[metadata][user_id]", request.userId().toString());
        if (request.customerId() != null) {
            form.add("customer", request.customerId());
        } else {
            form.add("customer_email", request.email());
        }
        return post("/v1/checkout/sessions", form);
    }

    @Override
    public URI portal(String customerId, String returnUrl) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("customer", customerId);
        form.add("return_url", returnUrl);
        return post("/v1/billing_portal/sessions", form);
    }

    private URI post(String path, MultiValueMap<String, String> form) {
        Map<?, ?> response = http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null || !(response.get("url") instanceof String url)) {
            throw new IllegalStateException("Resposta do Stripe sem url");
        }
        return URI.create(url);
    }
}
