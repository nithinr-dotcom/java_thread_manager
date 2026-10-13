package com.fleet.storefront.domain.payment;

import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.shared.DomainException;

import java.util.Map;

public final class PaymentExceedsDueException extends DomainException {

    private final Money due;

    public PaymentExceedsDueException(Money amount, Money due) {
        super("PAYMENT_EXCEEDS_DUE", "Payment of " + amount + " exceeds the amount due " + due);
        this.due = due;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("amountDue", due.amount());
    }
}
