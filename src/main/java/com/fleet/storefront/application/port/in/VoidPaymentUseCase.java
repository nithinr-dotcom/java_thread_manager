package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.payment.PaymentId;
import com.fleet.storefront.domain.shared.Sha256;

import java.util.Objects;

/**
 * Hands a tender back while its order is still awaiting payment (split tender abandoned), so the order
 * can then be cancelled. Paid orders are corrected by refund documents instead. Idempotent per key.
 */
public interface VoidPaymentUseCase {

    VoidResult voidPayment(VoidPaymentCommand command);

    record VoidPaymentCommand(TerminalId terminalId, String idempotencyKey, OrderId orderId, PaymentId paymentId,
                              String reason) {

        public VoidPaymentCommand {
            Objects.requireNonNull(terminalId, "terminalId");
            IdempotentCommand.requireKey(idempotencyKey);
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(paymentId, "paymentId");
            reason = IdempotentCommand.requireReason(reason);
        }

        public String fingerprint() {
            return Sha256.hex("void:" + orderId.value() + ":" + paymentId.value() + ":" + reason);
        }
    }

    record VoidResult(OrderAccount account, boolean replayed) {
    }
}
