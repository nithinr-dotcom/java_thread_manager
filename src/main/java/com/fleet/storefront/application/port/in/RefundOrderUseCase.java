package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.payment.PaymentMethod;
import com.fleet.storefront.domain.refund.Refund;
import com.fleet.storefront.domain.shared.Sha256;

import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** Issues a refund document for units of a paid order. Idempotent per (terminal, key). */
public interface RefundOrderUseCase {

    RefundResult refund(RefundOrderCommand command);

    /** @param unitsByLine units to refund per order line number */
    record RefundOrderCommand(TerminalId terminalId, String idempotencyKey, OrderId orderId,
                              SortedMap<Integer, Integer> unitsByLine, boolean restock, PaymentMethod method,
                              String reason) {

        public RefundOrderCommand {
            Objects.requireNonNull(terminalId, "terminalId");
            IdempotentCommand.requireKey(idempotencyKey);
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(method, "method");
            unitsByLine = new TreeMap<>(Objects.requireNonNull(unitsByLine, "unitsByLine"));
            if (unitsByLine.isEmpty()) {
                throw new IllegalArgumentException("A refund needs at least one line");
            }
            unitsByLine.values().forEach(q -> {
                if (q == null || q < 1) {
                    throw new IllegalArgumentException("Refund quantities must be positive");
                }
            });
            reason = IdempotentCommand.requireReason(reason);
        }

        public String fingerprint() {
            StringBuilder s = new StringBuilder("refund:").append(orderId.value()).append(':');
            for (Map.Entry<Integer, Integer> e : unitsByLine.entrySet()) {
                s.append(e.getKey()).append('=').append(e.getValue()).append(';');
            }
            return Sha256.hex(s.append(':').append(restock).append(':').append(method).append(':').append(reason)
                    .toString());
        }
    }

    record RefundResult(Refund refund, OrderAccount account, boolean replayed) {
    }
}
