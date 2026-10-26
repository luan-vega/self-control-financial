package com.selfcontrol.app.account;

import java.util.Currency;
import java.util.List;

/** Moedas oferecidas no cadastro e no perfil (a moeda padrão vale para relatórios e novos lançamentos). */
public final class SupportedCurrencies {

    public static final List<String> CODES = List.of("BRL", "USD", "EUR", "GBP", "ARS", "CLP", "MXN", "CAD", "AUD", "JPY");

    private SupportedCurrencies() {
    }

    public static boolean isSupported(String code) {
        return code != null && CODES.contains(code);
    }

    public static Currency of(String code) {
        return Currency.getInstance(code);
    }
}
