package com.fleet.storefront.domain.shared;

import java.util.Map;

/**
 * Base type for violations of business rules. Carries a stable, machine-readable code that
 * delivery mechanisms (HTTP, messaging) can expose to clients without knowing the concrete type.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Extra machine-readable facts about the violation (e.g. the SKU that is out of stock). */
    public Map<String, Object> details() {
        return Map.of();
    }
}
