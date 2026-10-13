package com.fleet.storefront.domain.terminal;

import com.fleet.storefront.domain.shared.DomainException;

public final class LastManagerException extends DomainException {

    public LastManagerException() {
        super("LAST_MANAGER", "The last active manager device cannot be revoked; register another manager first");
    }
}
