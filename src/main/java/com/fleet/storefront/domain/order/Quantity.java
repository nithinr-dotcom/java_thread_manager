package com.fleet.storefront.domain.order;

/** Positive, bounded number of units on an order line. */
public record Quantity(int value) {

    public static final int MAX_PER_LINE = 999;

    public Quantity {
        if (value < 1 || value > MAX_PER_LINE) {
            throw new IllegalArgumentException("Quantity must be within 1.." + MAX_PER_LINE + ": " + value);
        }
    }

    public static Quantity of(int value) {
        return new Quantity(value);
    }

    public Quantity plus(Quantity other) {
        return new Quantity(value + other.value);
    }
}
