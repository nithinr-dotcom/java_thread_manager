package com.fleet.storefront.domain.payment;

import com.fleet.storefront.domain.shared.UuidV7;

import java.util.Objects;
import java.util.UUID;

public record PaymentId(UUID value) {

    public PaymentId {
        Objects.requireNonNull(value, "value");
    }

    public static PaymentId newId() {
        return new PaymentId(UuidV7.next());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
