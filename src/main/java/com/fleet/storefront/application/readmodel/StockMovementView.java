package com.fleet.storefront.application.readmodel;

import java.time.Instant;
import java.util.UUID;

public record StockMovementView(UUID id, String sku, int delta, String reason, UUID referenceId, String terminalId,
                                String note, int onHandAfter, Instant createdAt) {
}
