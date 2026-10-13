package com.fleet.storefront.application.service;

import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.shared.ValidationException;

import java.math.BigDecimal;
import java.util.Currency;

/** Converts client-supplied decimals into {@link Money}, rejecting more precision than the currency has. */
final class Amounts {

    private Amounts() {
    }

    static Money exact(BigDecimal amount, Currency currency, String field) {
        if (amount == null) {
            return null;
        }
        try {
            return Money.of(amount, currency);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("'" + field + "': " + e.getMessage());
        }
    }
}
