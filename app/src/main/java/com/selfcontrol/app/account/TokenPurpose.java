package com.selfcontrol.app.account;

import java.time.Duration;

/** Para que serve um link enviado por e-mail, e por quanto tempo vale. */
enum TokenPurpose {

    VERIFY_EMAIL(Duration.ofDays(3)),
    RESET_PASSWORD(Duration.ofHours(1));

    final Duration ttl;

    TokenPurpose(Duration ttl) {
        this.ttl = ttl;
    }
}
