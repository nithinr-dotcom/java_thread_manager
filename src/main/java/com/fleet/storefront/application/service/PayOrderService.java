package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.IdempotencyKeyReusedException;
import com.fleet.storefront.application.port.in.OrderNotFoundException;
import com.fleet.storefront.application.port.in.PayOrderUseCase;
import com.fleet.storefront.application.port.in.VoidPaymentUseCase;
import com.fleet.storefront.application.port.out.IdempotencyStore;
import com.fleet.storefront.application.port.out.OrderRepository;
import com.fleet.storefront.application.port.out.Outbox;
import com.fleet.storefront.application.port.out.TransactionRunner;
import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.event.PaymentRecorded;
import com.fleet.storefront.domain.order.event.PaymentVoided;
import com.fleet.storefront.domain.payment.Payment;
import com.fleet.storefront.domain.payment.PaymentId;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;

/**
 * Takes a tender. One transaction; locks: idempotency key → order row. Payments of the same order
 * serialize on the order row, so concurrent tenders can never overpay it.
 */
public final class PayOrderService implements PayOrderUseCase, VoidPaymentUseCase {

    private final TransactionRunner transactions;
    private final IdempotencyStore idempotency;
    private final OrderRepository orders;
    private final Outbox outbox;
    private final Clock clock;

    public PayOrderService(TransactionRunner transactions, IdempotencyStore idempotency, OrderRepository orders,
                           Outbox outbox, Clock clock) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public PaymentResult pay(PayOrderCommand command) {
        String fingerprint = command.fingerprint();
        return transactions.inTransaction(() -> {
            IdempotencyStore.Claim claim = idempotency.claim(command.terminalId(), command.idempotencyKey(),
                    fingerprint, clock.instant());
            if (claim instanceof IdempotencyStore.AlreadyCompleted done) {
                if (!done.requestFingerprint().equals(fingerprint)) {
                    throw new IdempotencyKeyReusedException(command.idempotencyKey());
                }
                OrderAccount account = orders.findAccount(command.orderId())
                        .orElseThrow(() -> new OrderNotFoundException(command.orderId()));
                Payment original = account.payments().stream()
                        .filter(p -> p.id().value().equals(done.resourceId()))
                        .findFirst()
                        .orElseThrow(() -> new IdempotencyKeyReusedException(command.idempotencyKey()));
                return new PaymentResult(original, account, true);
            }

            OrderAccount account = orders.lockAccount(command.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(command.orderId()));
            Currency currency = account.order().currency();
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Payment payment = account.pay(PaymentId.newId(), command.method(),
                    Amounts.exact(command.amount(), currency, "amount"),
                    Amounts.exact(command.tendered(), currency, "tendered"),
                    command.reference(), command.terminalId(), now);
            OrderAccount after = account.withPayment(payment);

            orders.insertPayment(payment);
            if (after.status() != account.status()) {
                orders.updateStatus(account.order().id(), after.status(), null);
            }
            idempotency.complete(command.terminalId(), command.idempotencyKey(), payment.id().value());
            outbox.append(new Outbox.Message(Outbox.Destination.HQ, PaymentRecorded.TYPE, payment.id().value(),
                    new PaymentRecorded(payment.id().value(), account.order().id().value(),
                            account.order().number().value(), payment.method().name(), currency.getCurrencyCode(),
                            payment.amount().amount(),
                            payment.tendered() == null ? null : payment.tendered().amount(),
                            payment.change() == null ? null : payment.change().amount(),
                            payment.reference(), payment.terminalId().value(), payment.receivedAt(),
                            after.status().name())));
            return new PaymentResult(payment, after, false);
        });
    }

    /** Locks: idempotency key → order row. */
    @Override
    public VoidResult voidPayment(VoidPaymentCommand command) {
        String fingerprint = command.fingerprint();
        return transactions.inTransaction(() -> {
            IdempotencyStore.Claim claim = idempotency.claim(command.terminalId(), command.idempotencyKey(),
                    fingerprint, clock.instant());
            if (claim instanceof IdempotencyStore.AlreadyCompleted done) {
                if (!done.requestFingerprint().equals(fingerprint)) {
                    throw new IdempotencyKeyReusedException(command.idempotencyKey());
                }
                return new VoidResult(orders.findAccount(command.orderId())
                        .orElseThrow(() -> new OrderNotFoundException(command.orderId())), true);
            }
            OrderAccount account = orders.lockAccount(command.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(command.orderId()));
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            OrderAccount after = account.voidPayment(command.paymentId(), command.reason(), now);
            Payment voided = after.payments().stream().filter(p -> p.id().equals(command.paymentId())).findFirst()
                    .orElseThrow();
            orders.voidPayment(voided);
            idempotency.complete(command.terminalId(), command.idempotencyKey(), command.paymentId().value());
            outbox.append(new Outbox.Message(Outbox.Destination.HQ, PaymentVoided.TYPE, voided.id().value(),
                    new PaymentVoided(voided.id().value(), account.order().id().value(), account.order().number().value(),
                            command.reason(), command.terminalId().value(), now)));
            return new VoidResult(after, false);
        });
    }
}
