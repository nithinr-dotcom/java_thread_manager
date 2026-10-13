package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.shared.DomainException;

import java.util.UUID;

public final class TicketNotFoundException extends DomainException {

    public TicketNotFoundException(UUID id) {
        super("TICKET_NOT_FOUND", "No kitchen ticket with id " + id);
    }
}
