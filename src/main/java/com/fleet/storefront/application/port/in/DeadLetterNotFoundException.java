package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.shared.DomainException;

import java.util.UUID;

public final class DeadLetterNotFoundException extends DomainException {

    public DeadLetterNotFoundException(UUID id) {
        super("DEAD_LETTER_NOT_FOUND", "No dead-lettered outbox event with id " + id);
    }
}
