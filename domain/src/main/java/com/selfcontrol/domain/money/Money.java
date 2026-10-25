package com.selfcontrol.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Valor monetário imutável, guardado como inteiro na menor unidade da moeda
 * (centavos para BRL/USD). Nunca usa ponto flutuante.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Moeda sem casas decimais definidas: " + currency);
        }
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    /** Converte um valor decimal ("12.34") para unidades menores. Rejeita casas extras. */
    public static Money parse(String amount, String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode);
        BigDecimal value = new BigDecimal(amount.trim())
                .setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
        return new Money(value.unscaledValue().longValueExact(), currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + toDecimal().toPlainString();
    }
}
