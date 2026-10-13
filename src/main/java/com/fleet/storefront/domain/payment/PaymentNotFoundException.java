package com.fleet.storefront.domain.payment;

import com.fleet.storefront.domain.shared.DomainException;

public final class PaymentNotFoundException extends DomainException {

    public PaymentNotFoundException(PaymentId id) {
        super("PAYMENT_NOT_FOUND", "The order has no payment " + id);
    }
}
