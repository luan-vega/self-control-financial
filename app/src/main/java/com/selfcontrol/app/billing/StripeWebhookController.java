package com.selfcontrol.app.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;

/**
 * Webhook do Stripe: confirma pagamentos e mudanças da assinatura. Público para o Spring Security,
 * mas só aceita eventos assinados com o segredo do endpoint (STRIPE_WEBHOOK_SECRET). Responde 2xx
 * também para eventos repetidos ou ignorados, senão o Stripe fica reenviando.
 */
@RestController
public class StripeWebhookController {

    public static final String PATH = "/api/billing/webhook";

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final BillingService billing;
    private final JsonMapper json;
    private final String secret;
    private final Clock clock;

    StripeWebhookController(BillingService billing, JsonMapper json,
                            @Value("${app.billing.stripe.webhook-secret:}") String secret, Clock clock) {
        this.billing = billing;
        this.json = json;
        this.secret = secret;
        this.clock = clock;
    }

    @PostMapping(PATH)
    ResponseEntity<?> receive(@RequestBody byte[] payload,
                              @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        if (secret.isBlank()) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "Webhook do Stripe não configurado");
        }
        if (!StripeSignature.isValid(payload, signature, secret, clock.instant())) {
            log.atWarn().addKeyValue("billing.event", "invalid_signature").log("Webhook do Stripe com assinatura inválida");
            return problem(HttpStatus.BAD_REQUEST, "Assinatura inválida");
        }
        JsonNode event;
        try {
            event = json.readTree(payload);
        } catch (JacksonException e) {
            return problem(HttpStatus.BAD_REQUEST, "JSON inválido");
        }
        BillingService.Outcome outcome = billing.handle(event);
        log.atInfo()
                .addKeyValue("billing.event_id", event.path("id").asString())
                .addKeyValue("billing.event_type", event.path("type").asString())
                .addKeyValue("billing.outcome", outcome.name())
                .log("Webhook do Stripe {} ({}): {}", event.path("type").asString(), event.path("id").asString(), outcome);
        return ResponseEntity.ok(Map.of("received", true, "outcome", outcome.name()));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
