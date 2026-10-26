package com.selfcontrol.app.shared.security;

import java.util.Currency;
import java.util.UUID;

/** Usuário dono da transação de banco atual (o mesmo que o RLS enxerga). */
public record CurrentUser(UUID id, Currency defaultCurrency) {
}
