package com.fleet.storefront.domain.refund;

import com.fleet.storefront.domain.shared.DomainException;

import java.util.Map;

public final class RefundExceedsSoldException extends DomainException {

    private final int lineNo;
    private final int refundable;

    public RefundExceedsSoldException(int lineNo, int requested, int refundable) {
        super("REFUND_EXCEEDS_SOLD",
                "Line " + lineNo + ": cannot refund " + requested + " unit(s), only " + refundable + " left to refund");
        this.lineNo = lineNo;
        this.refundable = refundable;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("lineNo", lineNo, "refundable", refundable);
    }
}
