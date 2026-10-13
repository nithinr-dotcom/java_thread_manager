package com.fleet.storefront.domain.catalog;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stock keeping unit code, normalised to upper case. */
public record Sku(String value) implements Comparable<Sku> {

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,63}");

    public Sku {
        Objects.requireNonNull(value, "value");
        value = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid SKU: '" + value + "'");
        }
    }

    public static Sku of(String value) {
        return new Sku(value);
    }

    @Override
    public int compareTo(Sku other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
