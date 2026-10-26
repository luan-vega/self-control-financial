package com.selfcontrol.app.billing;

/** Quem cobra: o Stripe de verdade ou a simulação local (app.billing.provider). */
public enum BillingProvider {
    MOCK, STRIPE
}
