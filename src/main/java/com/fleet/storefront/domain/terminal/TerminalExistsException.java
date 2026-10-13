package com.fleet.storefront.domain.terminal;

import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.shared.DomainException;

public final class TerminalExistsException extends DomainException {

    public TerminalExistsException(TerminalId terminalId) {
        super("TERMINAL_EXISTS", "A device with id " + terminalId + " is already registered");
    }
}
