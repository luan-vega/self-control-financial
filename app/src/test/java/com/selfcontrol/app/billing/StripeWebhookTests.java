package com.selfcontrol.app.billing;

import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Webhook do Stripe: assinatura conferida, eventos aplicados uma vez só e plano atualizado. */
class StripeWebhookTests extends IntegrationTest {

    @Test
    void checkoutCompletedTurnsTheAccountPro() throws Exception {
        TestUser user = newUser();
        String customer = "cus_" + UUID.randomUUID();
        String event = event("checkout.session.completed", """
                {"client_reference_id": "%s", "customer": "%s", "subscription": "sub_%s"}
                """.formatted(user.id(), customer, user.id()));

        send(event).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));

        assertThat(plan(user)).isEqualTo("PRO/ACTIVE/STRIPE");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + apiKey(user, true)))
                .andExpect(jsonPath("$.plan").value("PRO"))
                .andExpect(jsonPath("$.usage.monthlyLimit").doesNotExist());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE subject_id = :id AND action = 'billing.plan_changed' AND channel = 'WEBHOOK'")
                .param("id", user.id()).query(Integer.class).single()).isEqualTo(1);

        // O Stripe reenvia o mesmo evento: não aplica de novo
        send(event).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
    }

    @Test
    void subscriptionLifecycle() throws Exception {
        TestUser user = newUser();
        String customer = "cus_" + UUID.randomUUID();
        String sub = "sub_" + UUID.randomUUID();
        send(event("checkout.session.completed", """
                {"client_reference_id": "%s", "customer": "%s", "subscription": "%s"}
                """.formatted(user.id(), customer, sub))).andExpect(status().isOk());

        long periodEnd = Instant.now().plusSeconds(30L * 24 * 3600).getEpochSecond();
        // Pagamento falhou: continua Pro, mas em atraso
        send(event("invoice.payment_failed", "{\"customer\": \"%s\"}".formatted(customer)))
                .andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertThat(plan(user)).isEqualTo("PRO/PAST_DUE/STRIPE");
        mvc.perform(get("/billing").with(user.login()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("Atualize o cartão")));

        send(event("invoice.paid", "{\"customer\": \"%s\"}".formatted(customer)));
        assertThat(plan(user)).isEqualTo("PRO/ACTIVE/STRIPE");

        // Cancelou pelo portal: vale até o fim do período (formato novo: período no item)
        send(event("customer.subscription.updated", """
                {"id": "%s", "customer": "%s", "status": "active", "cancel_at_period_end": true,
                 "items": {"data": [{"current_period_end": %d}]}}
                """.formatted(sub, customer, periodEnd)));
        assertThat(plan(user)).isEqualTo("PRO/ACTIVE/STRIPE");
        assertThat(jdbc.sql("SELECT cancel_at_period_end FROM subscription WHERE user_id = :id")
                .param("id", user.id()).query(Boolean.class).single()).isTrue();

        // Fim do período: assinatura apagada, volta ao Free
        send(event("customer.subscription.deleted", """
                {"id": "%s", "customer": "%s", "status": "canceled", "current_period_end": %d}
                """.formatted(sub, customer, periodEnd)));
        assertThat(plan(user)).isEqualTo("FREE/CANCELED/STRIPE");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + apiKey(user, true)))
                .andExpect(jsonPath("$.plan").value("FREE"));
    }

    @Test
    void subscriptionCreatedCanFindTheAccountByMetadata() throws Exception {
        TestUser user = newUser();
        send(event("customer.subscription.created", """
                {"id": "sub_%s", "customer": "cus_%s", "status": "trialing", "metadata": {"user_id": "%s"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), user.id())))
                .andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertThat(plan(user)).isEqualTo("PRO/ACTIVE/STRIPE");
    }

    @Test
    void ignoresUnknownAccountsAndEventTypes() throws Exception {
        send(event("checkout.session.completed", "{\"client_reference_id\": \"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(jsonPath("$.outcome").value("UNKNOWN_ACCOUNT"));
        send(event("checkout.session.completed", "{\"client_reference_id\": \"nao-e-uuid\"}"))
                .andExpect(jsonPath("$.outcome").value("UNKNOWN_ACCOUNT"));
        send(event("invoice.paid", "{\"customer\": \"cus_desconhecido\"}"))
                .andExpect(jsonPath("$.outcome").value("UNKNOWN_ACCOUNT"));
        send(event("customer.subscription.updated", "{\"id\": \"sub_x\", \"customer\": \"cus_x\", \"status\": \"active\"}"))
                .andExpect(jsonPath("$.outcome").value("UNKNOWN_ACCOUNT"));
        send(event("charge.refunded", "{}")).andExpect(jsonPath("$.outcome").value("IGNORED"));
        send("{\"type\": \"sem.id\"}").andExpect(jsonPath("$.outcome").value("IGNORED"));
    }

    @Test
    void rejectsBadSignaturesOldEventsAndGarbage() throws Exception {
        TestUser user = newUser();
        String body = event("checkout.session.completed", "{\"client_reference_id\": \"%s\"}".formatted(user.id()));
        long now = Instant.now().getEpochSecond();

        // Sem assinatura, assinada com outro segredo, ou antiga (replay)
        mvc.perform(post(StripeWebhookController.PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        send(body, "t=" + now + ",v1=" + StripeSignature.sign(now, bytes(body), "whsec_outro")).andExpect(status().isBadRequest());
        long old = now - 600;
        send(body, "t=" + old + ",v1=" + StripeSignature.sign(old, bytes(body), STRIPE_SECRET)).andExpect(status().isBadRequest());
        send(body, "t=abc,v1=00").andExpect(status().isBadRequest());
        send(body, "v1=00").andExpect(status().isBadRequest());
        assertThat(plan(user)).isEqualTo("none");

        // Assinatura válida, mas corpo que não é JSON
        String garbage = "isto não é json";
        send(garbage, "t=" + now + ",v1=" + StripeSignature.sign(now, bytes(garbage), STRIPE_SECRET))
                .andExpect(status().isBadRequest());
    }

    private ResultActions send(String body) throws Exception {
        long now = Instant.now().getEpochSecond();
        return send(body, "t=" + now + ",v1=" + StripeSignature.sign(now, bytes(body), STRIPE_SECRET));
    }

    private ResultActions send(String body, String signature) throws Exception {
        return mvc.perform(post(StripeWebhookController.PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signature)
                .content(bytes(body)));
    }

    private static String event(String type, String object) {
        return """
                {"id": "evt_%s", "type": "%s", "data": {"object": %s}}
                """.formatted(UUID.randomUUID(), type, object);
    }

    private String plan(TestUser user) {
        return jdbc.sql("SELECT plan || '/' || status || '/' || provider FROM subscription WHERE user_id = :id")
                .param("id", user.id()).query(String.class).optional().orElse("none");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
