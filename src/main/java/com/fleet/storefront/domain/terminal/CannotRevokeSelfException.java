package com.fleet.storefront.domain.terminal;

import com.fleet.storefront.domain.shared.DomainException;

public final class CannotRevokeSelfException extends DomainException {

    public CannotRevokeSelfException() {
        super("CANNOT_REVOKE_SELF", "A device cannot revoke its own credentials");
    }
}
