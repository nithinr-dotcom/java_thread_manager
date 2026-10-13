package com.fleet.storefront.application.port.in;

/** Upper bound on the number of rows a query returns. */
public record Limit(int value) {

    public static final int MAX = 200;

    public Limit {
        if (value < 1 || value > MAX) {
            throw new IllegalArgumentException("Limit must be within 1.." + MAX + ": " + value);
        }
    }
}
