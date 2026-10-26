package com.selfcontrol.app.billing;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Chamadas à API REST do Stripe (Checkout e Customer Portal), sem rede. */
class StripePaymentGatewayTest {

    @Test
    void createsCheckoutSessionLinkedToTheAccount() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer stripe = MockRestServiceServer.bindTo(builder).build();
        StripePaymentGateway gateway = new StripePaymentGateway(builder, "sk_test_123", "price_pro");
        UUID user = UUID.randomUUID();

        stripe.expect(requestTo("https://api.stripe.com/v1/checkout/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk_test_123"))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "mode", "subscription",
                        "line_items[0][price]", "price_pro",
                        "client_reference_id", user.toString(),
                        "customer_email", "ana@example.com",
                        "success_url", "https://app/billing?upgraded")))
                .andRespond(withSuccess("{\"url\": \"https://checkout.stripe.com/c/pay/cs_1\"}", MediaType.APPLICATION_JSON));

        URI checkout = gateway.checkout(new PaymentGateway.CheckoutRequest(user, "ana@example.com", null,
                "https://app/billing?upgraded", "https://app/billing?canceled"));

        assertThat(checkout).hasToString("https://checkout.stripe.com/c/pay/cs_1");
        assertThat(gateway.provider()).isEqualTo(BillingProvider.STRIPE);
        stripe.verify();
    }

    @Test
    void reusesTheCustomerAndOpensThePortal() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer stripe = MockRestServiceServer.bindTo(builder).build();
        StripePaymentGateway gateway = new StripePaymentGateway(builder, "sk_test_123", "price_pro");

        stripe.expect(requestTo("https://api.stripe.com/v1/checkout/sessions"))
                .andExpect(content().formDataContains(java.util.Map.of("customer", "cus_9")))
                .andRespond(withSuccess("{\"url\": \"https://checkout.stripe.com/c/pay/cs_2\"}", MediaType.APPLICATION_JSON));
        stripe.expect(requestTo("https://api.stripe.com/v1/billing_portal/sessions"))
                .andExpect(content().formDataContains(java.util.Map.of("customer", "cus_9", "return_url", "https://app/billing")))
                .andRespond(withSuccess("{\"url\": \"https://billing.stripe.com/p/session/1\"}", MediaType.APPLICATION_JSON));
        stripe.expect(requestTo("https://api.stripe.com/v1/billing_portal/sessions"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        gateway.checkout(new PaymentGateway.CheckoutRequest(UUID.randomUUID(), "x@example.com", "cus_9", "s", "c"));
        assertThat(gateway.portal("cus_9", "https://app/billing")).hasToString("https://billing.stripe.com/p/session/1");
        assertThatThrownBy(() -> gateway.portal("cus_9", "https://app/billing")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutKeys() {
        assertThatThrownBy(() -> new StripePaymentGateway(RestClient.builder(), "", "price"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STRIPE_SECRET_KEY");
    }
}
