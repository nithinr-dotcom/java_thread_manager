package com.fleet.storefront.domain.order;

/**
 * Lifecycle of an issued order. Only the status changes; the fiscal content (number, lines, totals)
 * is immutable.
 */
public enum OrderStatus {
    /** Checked out and committed; stock taken and fiscal number issued; awaiting (full) payment. */
    PLACED,
    /** Payments cover the total. */
    PAID,
    /** Voided before any payment; stock returned. The number stays used (gap-free). */
    CANCELLED,
    /** Some lines (or units) refunded through refund documents. */
    PARTIALLY_REFUNDED,
    /** Every unit refunded. */
    REFUNDED;

    public boolean isRefundable() {
        return this == PAID || this == PARTIALLY_REFUNDED;
    }
}
