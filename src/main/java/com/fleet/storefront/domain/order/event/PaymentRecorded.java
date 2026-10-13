package com.fleet.storefront.domain.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Replicated to HQ: a tender was taken for an order. */
public record PaymentRecorded(UUID paymentId, UUID orderId, String orderNumber, String method, String currency,
                              BigDecimal amount, BigDecimal tendered, BigDecimal change, String reference,
                              String terminalId, Instant receivedAt, String orderStatus) {

    public static final String TYPE = "PaymentRecorded";
}
