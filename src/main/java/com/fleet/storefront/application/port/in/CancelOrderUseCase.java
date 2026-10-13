package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.shared.Sha256;

import java.util.Objects;

/** Voids an unpaid order: returns its stock and cancels its kitchen ticket. Idempotent per (terminal, key). */
public interface CancelOrderUseCase {

    CancelResult cancel(CancelOrderCommand command);

    record CancelOrderCommand(TerminalId terminalId, String idempotencyKey, OrderId orderId, String reason) {

        public CancelOrderCommand {
            Objects.requireNonNull(terminalId, "terminalId");
            IdempotentCommand.requireKey(idempotencyKey);
            Objects.requireNonNull(orderId, "orderId");
            reason = IdempotentCommand.requireReason(reason);
        }

        public String fingerprint() {
            return Sha256.hex("cancel:" + orderId + ":" + reason);
        }
    }

    record CancelResult(OrderAccount account, boolean replayed) {
    }
}
