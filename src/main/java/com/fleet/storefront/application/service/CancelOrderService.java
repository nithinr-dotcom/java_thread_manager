package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.CancelOrderUseCase;
import com.fleet.storefront.application.port.in.IdempotencyKeyReusedException;
import com.fleet.storefront.application.port.in.OrderNotFoundException;
import com.fleet.storefront.application.port.out.IdempotencyStore;
import com.fleet.storefront.application.port.out.InventoryLedger;
import com.fleet.storefront.application.port.out.OrderRepository;
import com.fleet.storefront.application.port.out.Outbox;
import com.fleet.storefront.application.port.out.TransactionRunner;
import com.fleet.storefront.domain.inventory.StockChange;
import com.fleet.storefront.domain.inventory.StockChangeReason;
import com.fleet.storefront.domain.inventory.StockRequirement;
import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.OrderDraft;
import com.fleet.storefront.domain.order.event.KitchenTicketCancelled;
import com.fleet.storefront.domain.order.event.OrderCancelled;

import java.time.Clock;
import java.time.Instant;

/**
 * Voids an unpaid order. One transaction; locks: idempotency key → order row → inventory rows (product
 * id order, increments only). The fiscal number stays used: the document is marked cancelled, never
 * deleted, so numbering stays gap-free. The kitchen learns about it through the outbox.
 */
public final class CancelOrderService implements CancelOrderUseCase {

    private final TransactionRunner transactions;
    private final IdempotencyStore idempotency;
    private final OrderRepository orders;
    private final InventoryLedger inventory;
    private final Outbox outbox;
    private final Clock clock;

    public CancelOrderService(TransactionRunner transactions, IdempotencyStore idempotency, OrderRepository orders,
                              InventoryLedger inventory, Outbox outbox, Clock clock) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.orders = orders;
        this.inventory = inventory;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public CancelResult cancel(CancelOrderCommand command) {
        String fingerprint = command.fingerprint();
        return transactions.inTransaction(() -> {
            IdempotencyStore.Claim claim = idempotency.claim(command.terminalId(), command.idempotencyKey(),
                    fingerprint, clock.instant());
            if (claim instanceof IdempotencyStore.AlreadyCompleted done) {
                if (!done.requestFingerprint().equals(fingerprint)) {
                    throw new IdempotencyKeyReusedException(command.idempotencyKey());
                }
                return new CancelResult(orders.findAccount(command.orderId())
                        .orElseThrow(() -> new OrderNotFoundException(command.orderId())), true);
            }

            OrderAccount account = orders.lockAccount(command.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(command.orderId()));
            OrderAccount cancelled = account.cancel();
            Order order = account.order();

            StockChange change = new StockChange(StockChangeReason.CANCEL, order.id().value(),
                    command.terminalId().value(), command.reason());
            for (StockRequirement r : new OrderDraft(order.currency(), order.lines()).stockRequirements()) {
                inventory.add(r.productId(), r.quantity().value(), change);
            }
            orders.updateStatus(order.id(), cancelled.status(), command.reason());
            idempotency.complete(command.terminalId(), command.idempotencyKey(), order.id().value());

            Instant now = clock.instant();
            order.kitchenTicketCancellation(now).ifPresent(ticket -> outbox.append(new Outbox.Message(
                    Outbox.Destination.KITCHEN, KitchenTicketCancelled.TYPE, order.id().value(), ticket)));
            outbox.append(new Outbox.Message(Outbox.Destination.HQ, OrderCancelled.TYPE, order.id().value(),
                    new OrderCancelled(order.id().value(), order.number().value(), command.reason(),
                            command.terminalId().value(), now)));
            return new CancelResult(cancelled, false);
        });
    }
}
