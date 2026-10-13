package com.fleet.storefront.domain.inventory;

/** Why stock on hand changed; recorded on every stock movement. */
public enum StockChangeReason {
    SALE(false), CANCEL(false), REFUND(false), RESTOCK(false),
    WASTE(true), DAMAGE(true), COUNT_CORRECTION(true);

    private final boolean manualAdjustment;

    StockChangeReason(boolean manualAdjustment) {
        this.manualAdjustment = manualAdjustment;
    }

    /** Reasons an operator may use for a manual stock adjustment. */
    public boolean isManualAdjustment() {
        return manualAdjustment;
    }
}
