package com.fleet.storefront.domain.shared;

/**
 * Client input that is well-formed but not acceptable for this business context (e.g. an amount with
 * more decimals than the order's currency allows). Mapped to {@code 400 INVALID_REQUEST}.
 */
public final class ValidationException extends DomainException {

    public static final String CODE = "INVALID_REQUEST";

    public ValidationException(String message) {
        super(CODE, message);
    }
}
