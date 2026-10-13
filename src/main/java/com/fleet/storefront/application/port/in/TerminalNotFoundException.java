package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.shared.DomainException;

public final class TerminalNotFoundException extends DomainException {

    public TerminalNotFoundException(TerminalId id) {
        super("TERMINAL_NOT_FOUND", "No device with id " + id);
    }
}
