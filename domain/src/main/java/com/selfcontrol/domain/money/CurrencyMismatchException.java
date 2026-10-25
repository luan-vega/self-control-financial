package com.selfcontrol.domain.money;

import java.util.Currency;

public final class CurrencyMismatchException extends IllegalArgumentException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Moedas diferentes: " + expected.getCurrencyCode() + " e " + actual.getCurrencyCode());
    }
}
