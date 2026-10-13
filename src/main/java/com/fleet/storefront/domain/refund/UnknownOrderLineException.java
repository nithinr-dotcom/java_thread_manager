package com.fleet.storefront.domain.refund;

import com.fleet.storefront.domain.shared.DomainException;

public final class UnknownOrderLineException extends DomainException {

    public UnknownOrderLineException(int lineNo) {
        super("UNKNOWN_ORDER_LINE", "The order has no line " + lineNo);
    }
}
