package com.fleet.storefront.domain.order;

import com.fleet.storefront.domain.shared.UuidV7;

import java.util.Objects;
import java.util.UUID;

public record OrderId(UUID value) {

    public OrderId {
        Objects.requireNonNull(value, "value");
    }

    public static OrderId newId() {
        return new OrderId(UuidV7.next());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
