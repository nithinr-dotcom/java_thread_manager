package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.IdempotencyKeyReusedException;
import com.fleet.storefront.application.port.in.OrderNotFoundException;
import com.fleet.storefront.application.port.in.RefundOrderUseCase;
import com.fleet.storefront.application.port.out.FiscalSequence;
import com.fleet.storefront.application.port.out.IdempotencyStore;
import com.fleet.storefront.application.port.out.InventoryLedger;
import com.fleet.storefront.application.port.out.OrderRepository;
import com.fleet.storefront.application.port.out.Outbox;
import com.fleet.storefront.application.port.out.TransactionRunner;
import com.fleet.storefront.domain.fiscal.FiscalAllocation;
import com.fleet.storefront.domain.fiscal.FiscalSeries;
import com.fleet.storefront.domain.inventory.StockChange;
import com.fleet.storefront.domain.inventory.StockChangeReason;
import com.fleet.storefront.domain.inventory.StockRequirement;
import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.event.RefundIssued;
import com.fleet.storefront.domain.refund.Refund;
import com.fleet.storefront.domain.refund.RefundDraft;
import com.fleet.storefront.domain.refund.RefundId;
import com.fleet.storefront.domain.refund.RefundNumber;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Issues a refund document. One transaction; locks follow the global order: idempotency key → order
 * row → inventory rows (product id order, only if restocking) → {@code REF} fiscal counter (last, so a
 * failed refund never consumes a number).
 */
public final class RefundOrderService implements RefundOrderUseCase {

    private final TransactionRunner transactions;
    private final IdempotencyStore idempotency;
    private final OrderRepository orders;
    private final InventoryLedger inventory;
    private final FiscalSequence fiscalSequence;
    private final Outbox outbox;
    private final BranchContext branch;
    private final Clock clock;

    public RefundOrderService(TransactionRunner transactions, IdempotencyStore idempotency, OrderRepository orders,
                              InventoryLedger inventory, FiscalSequence fiscalSequence, Outbox outbox,
                              BranchContext branch, Clock clock) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.orders = orders;
        this.inventory = inventory;
        this.fiscalSequence = fiscalSequence;
        this.outbox = outbox;
        this.branch = branch;
        this.clock = clock;
    }

    @Override
    public RefundResult refund(RefundOrderCommand command) {
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
                Refund original = account.refunds().stream()
                        .filter(r -> r.id().value().equals(done.resourceId()))
                        .findFirst()
                        .orElseThrow(() -> new IdempotencyKeyReusedException(command.idempotencyKey()));
                return new RefundResult(original, account, true);
            }

            OrderAccount account = orders.lockAccount(command.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(command.orderId()));
            RefundDraft draft = account.draftRefund(command.unitsByLine());
            RefundId refundId = RefundId.newId();

            if (command.restock()) {
                StockChange change = new StockChange(StockChangeReason.REFUND, refundId.value(),
                        command.terminalId().value(), command.reason());
                for (StockRequirement r : draft.stockToReturn()) {
                    inventory.add(r.productId(), r.quantity().value(), change);
                }
            }

            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            LocalDate businessDate = LocalDate.ofInstant(now, branch.zone());
            FiscalAllocation number = fiscalSequence.next(FiscalSeries.REF, branch.branchCode(), businessDate, now);
            Refund refund = draft.issue(refundId, new RefundNumber(branch.branchCode(), businessDate, number.sequence()),
                    command.method(), command.reason(), command.restock(), command.terminalId(), number.issuedAt(),
                    number.previousHash());
            fiscalSequence.recordHash(FiscalSeries.REF, branch.branchCode(), businessDate, number.sequence(),
                    refund.fiscalHash());

            OrderAccount after = account.withRefund(refund);
            orders.insertRefund(refund);
            orders.updateStatus(account.order().id(), after.status(), null);
            idempotency.complete(command.terminalId(), command.idempotencyKey(), refund.id().value());
            outbox.append(new Outbox.Message(Outbox.Destination.HQ, RefundIssued.TYPE, refund.id().value(),
                    event(refund, account.order())));
            return new RefundResult(refund, after, false);
        });
    }

    private static RefundIssued event(Refund refund, Order order) {
        return new RefundIssued(refund.id().value(), refund.number().value(), order.id().value(),
                order.number().value(), refund.method().name(), refund.reason(), refund.restocked(),
                refund.currency().getCurrencyCode(), refund.subtotal().amount(), refund.taxTotal().amount(),
                refund.total().amount(), refund.terminalId().value(), refund.issuedAt(),
                refund.lines().stream()
                        .map(l -> new RefundIssued.Line(l.lineNo(), l.sku().value(), l.quantity().value(),
                                l.net().amount(), l.tax().amount(), l.total().amount()))
                        .toList(),
                refund.fiscalHash());
    }
}
