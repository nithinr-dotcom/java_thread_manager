package com.fleet.storefront.domain.payment;

import com.fleet.storefront.domain.shared.DomainException;

public final class PaymentAlreadyVoidedException extends DomainException {

    public PaymentAlreadyVoidedException(PaymentId id) {
        super("PAYMENT_ALREADY_VOIDED", "Payment " + id + " was already voided");
    }
}
