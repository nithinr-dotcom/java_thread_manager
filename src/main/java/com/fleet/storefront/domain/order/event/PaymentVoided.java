package com.fleet.storefront.domain.order.event;

import java.time.Instant;
import java.util.UUID;

/** Replicated to HQ: a tender was handed back before its order was completed. */
public record PaymentVoided(UUID paymentId, UUID orderId, String orderNumber, String reason, String terminalId,
                            Instant voidedAt) {

    public static final String TYPE = "PaymentVoided";
}
