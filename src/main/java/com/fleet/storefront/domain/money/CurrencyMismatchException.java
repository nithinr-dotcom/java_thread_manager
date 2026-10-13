package com.fleet.storefront.domain.money;

import com.fleet.storefront.domain.shared.DomainException;

import java.util.Currency;

public final class CurrencyMismatchException extends DomainException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("CURRENCY_MISMATCH", "Cannot combine amounts in " + expected + " and " + actual);
    }
}
