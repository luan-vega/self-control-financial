package com.selfcontrol.app.api;

import com.selfcontrol.app.account.Profile;
import com.selfcontrol.app.account.ProfileService;
import com.selfcontrol.app.billing.BillingClient;
import com.selfcontrol.app.billing.Subscription;
import com.selfcontrol.domain.billing.Usage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Quem é o dono da credencial, o plano e o uso do mês. Bom primeiro teste de uma chave nova. */
@RestController
class MeApiController {

    /** limit nulo quando o plano é ilimitado. */
    record UsageResponse(int transactionsThisMonth, Integer monthlyLimit) {

        static UsageResponse of(Usage u) {
            return new UsageResponse(u.used(), u.unlimited() ? null : u.limit().getAsInt());
        }
    }

    record MeResponse(UUID id, String email, String name, String currency, boolean emailVerified, String plan,
                      String subscriptionStatus, UsageResponse usage) {
    }

    private final ProfileService profiles;
    private final BillingClient billing;

    MeApiController(ProfileService profiles, BillingClient billing) {
        this.profiles = profiles;
        this.billing = billing;
    }

    @GetMapping("/api/v1/me")
    MeResponse me() {
        Profile p = profiles.current();
        Subscription s = billing.subscription();
        return new MeResponse(p.id(), p.email(), p.name(), p.currency().getCurrencyCode(), p.emailVerified(),
                s.plan().name(), s.status().name(), UsageResponse.of(billing.usage()));
    }
}
