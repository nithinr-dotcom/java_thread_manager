package com.fleet.storefront.adapter.in.web;

import java.util.function.Supplier;

/**
 * Builds commands and value objects from request data. Their constructors throw
 * {@link IllegalArgumentException} on bad input; only here is that translated into a client error.
 * An {@code IllegalArgumentException} anywhere else is a bug and surfaces as {@code 500}.
 */
final class Requests {

    private Requests() {
    }

    static <T> T parse(Supplier<T> factory) {
        try {
            return factory.get();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestException(e.getMessage(), e);
        }
    }
}
