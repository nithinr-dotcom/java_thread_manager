package com.fleet.storefront.domain.refund;

import com.fleet.storefront.domain.shared.UuidV7;

import java.util.Objects;
import java.util.UUID;

public record RefundId(UUID value) {

    public RefundId {
        Objects.requireNonNull(value, "value");
    }

    public static RefundId newId() {
        return new RefundId(UuidV7.next());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
