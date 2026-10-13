package com.fleet.storefront.domain.payment;

import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.shared.DomainException;

public final class InsufficientTenderException extends DomainException {

    public InsufficientTenderException(Money amount, Money tendered) {
        super("INSUFFICIENT_TENDER", "Cash tendered " + tendered + " is less than the amount " + amount);
    }
}
