package com.selfcontrol.app.billing;

import java.util.Locale;

/** Situação da assinatura (coluna subscription.status). */
public enum SubscriptionStatus {

    ACTIVE, PAST_DUE, CANCELED;

    /** Traduz o status de uma assinatura do Stripe. */
    static SubscriptionStatus fromStripe(String status) {
        return switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
            case "active", "trialing" -> ACTIVE;
            case "past_due", "unpaid", "incomplete" -> PAST_DUE;
            default -> CANCELED;
        };
    }
}
