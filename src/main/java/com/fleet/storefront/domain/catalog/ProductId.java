package com.fleet.storefront.domain.catalog;

import java.util.Objects;
import java.util.UUID;

public record ProductId(UUID value) implements Comparable<ProductId> {

    public ProductId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public int compareTo(ProductId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
