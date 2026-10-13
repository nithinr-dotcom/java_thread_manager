package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.shared.DomainException;

public final class IdempotencyKeyReusedException extends DomainException {

    public IdempotencyKeyReusedException(String key) {
        super("IDEMPOTENCY_KEY_REUSED",
                "Idempotency key '" + key + "' was already used for a different request payload");
    }
}
