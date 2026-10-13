package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.payment.Payment;
import com.fleet.storefront.domain.payment.PaymentMethod;
import com.fleet.storefront.domain.shared.Sha256;

import java.math.BigDecimal;
import java.util.Objects;

/** Takes a tender for an order (split tender allowed). Idempotent per (terminal, key). */
public interface PayOrderUseCase {

    PaymentResult pay(PayOrderCommand command);

    /**
     * @param tendered  cash handed over; required for CASH, absent for CARD
     * @param reference card approval code; required for CARD, absent for CASH
     */
    record PayOrderCommand(TerminalId terminalId, String idempotencyKey, OrderId orderId, PaymentMethod method,
                           BigDecimal amount, BigDecimal tendered, String reference) {

        public PayOrderCommand {
            Objects.requireNonNull(terminalId, "terminalId");
            IdempotentCommand.requireKey(idempotencyKey);
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(amount, "amount");
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("Amount must not be negative");
            }
            if (method == PaymentMethod.CASH && (tendered == null || reference != null)) {
                throw new IllegalArgumentException("Cash payments need 'tendered' and no 'reference'");
            }
            if (method == PaymentMethod.CARD
                    && (tendered != null || reference == null || reference.isBlank() || reference.length() > 64)) {
                throw new IllegalArgumentException("Card payments need a 1..64 character 'reference' and no 'tendered'");
            }
        }

        public String fingerprint() {
            return Sha256.hex("payment:" + orderId + ":" + method + ":" + amount.stripTrailingZeros().toPlainString()
                    + ":" + (tendered == null ? "" : tendered.stripTrailingZeros().toPlainString())
                    + ":" + (reference == null ? "" : reference));
        }
    }

    record PaymentResult(Payment payment, OrderAccount account, boolean replayed) {
    }
}
