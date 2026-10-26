package com.selfcontrol.app.billing;

import com.selfcontrol.domain.billing.Plan;

import java.time.Instant;

/**
 * Assinatura da conta logada. plan já é o plano efetivo: uma assinatura Pro cancelada continua Pro
 * até o fim do período pago, e um pagamento atrasado (PAST_DUE) ainda não derruba o plano.
 */
public record Subscription(Plan plan, SubscriptionStatus status, BillingProvider provider, Instant currentPeriodEnd,
                           boolean cancelAtPeriodEnd, boolean hasCustomer) {

    static Subscription free() {
        return new Subscription(Plan.FREE, SubscriptionStatus.ACTIVE, null, null, false, false);
    }

    public boolean pro() {
        return plan == Plan.PRO;
    }

    public boolean pastDue() {
        return pro() && status == SubscriptionStatus.PAST_DUE;
    }
}
