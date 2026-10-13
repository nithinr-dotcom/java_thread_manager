package com.fleet.storefront.domain.order;

import java.util.Objects;
import java.util.regex.Pattern;

/** Identifier of the POS / kiosk / tablet that submitted an order. */
public record TerminalId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public TerminalId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid terminal id: '" + value + "'");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
