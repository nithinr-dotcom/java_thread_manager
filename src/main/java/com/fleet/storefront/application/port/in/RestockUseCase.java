package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.inventory.StockChangeReason;
import com.fleet.storefront.domain.order.TerminalId;

import java.util.Objects;

/**
 * Goods received (restock) and manual corrections (waste, damage, count). Every change is journaled and
 * idempotent per (device, key): a retried restock never adds the units twice.
 */
public interface RestockUseCase {

    StockLevel restock(RestockCommand command);

    StockLevel adjust(AdjustStockCommand command);

    record RestockCommand(Sku sku, int quantity, String note, TerminalId actor, String idempotencyKey) {

        public static final int MAX_QUANTITY = 100_000;

        public RestockCommand {
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(actor, "actor");
            IdempotentCommand.requireKey(idempotencyKey);
            if (quantity < 1 || quantity > MAX_QUANTITY) {
                throw new IllegalArgumentException("Restock quantity must be within 1.." + MAX_QUANTITY + ": " + quantity);
            }
            if (note != null && note.length() > 200) {
                throw new IllegalArgumentException("Note must be at most 200 characters");
            }
        }
    }

    record AdjustStockCommand(Sku sku, int delta, StockChangeReason reason, String note, TerminalId actor,
                              String idempotencyKey) {

        public AdjustStockCommand {
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(actor, "actor");
            IdempotentCommand.requireKey(idempotencyKey);
            if (delta == 0 || Math.abs(delta) > RestockCommand.MAX_QUANTITY) {
                throw new IllegalArgumentException("Delta must be non-zero and within ±" + RestockCommand.MAX_QUANTITY);
            }
            if (!reason.isManualAdjustment()) {
                throw new IllegalArgumentException("Adjustment reason must be WASTE, DAMAGE or COUNT_CORRECTION");
            }
            if (note != null && note.length() > 200) {
                throw new IllegalArgumentException("Note must be at most 200 characters");
            }
        }
    }

    /** @param replayed the request was a retry of an already applied change; {@code onHand} is the current stock */
    record StockLevel(Sku sku, int onHand, boolean replayed) {
    }
}
