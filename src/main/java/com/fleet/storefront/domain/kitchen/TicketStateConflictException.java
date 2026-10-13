package com.fleet.storefront.domain.kitchen;

import com.fleet.storefront.domain.shared.DomainException;

public final class TicketStateConflictException extends DomainException {

    public TicketStateConflictException(String message) {
        super("TICKET_STATE_CONFLICT", message);
    }
}
