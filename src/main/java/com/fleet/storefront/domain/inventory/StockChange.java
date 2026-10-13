package com.fleet.storefront.domain.inventory;

import java.util.Objects;
import java.util.UUID;

/**
 * Provenance of a stock movement: the reason, the document that caused it (order, refund) if any,
 * the terminal that caused it and an optional operator note.
 */
public record StockChange(StockChangeReason reason, UUID referenceId, String terminalId, String note) {

    public StockChange {
        Objects.requireNonNull(reason, "reason");
        if (note != null && note.length() > 200) {
            throw new IllegalArgumentException("Note must be at most 200 characters");
        }
        if (note != null && note.isBlank()) {
            note = null;
        }
    }

    public static StockChange of(StockChangeReason reason, UUID referenceId, String terminalId) {
        return new StockChange(reason, referenceId, terminalId, null);
    }
}
