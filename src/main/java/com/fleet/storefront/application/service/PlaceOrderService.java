package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.IdempotencyKeyReusedException;
import com.fleet.storefront.application.port.in.PlaceOrderCommand;
import com.fleet.storefront.application.port.in.PlaceOrderResult;
import com.fleet.storefront.application.port.in.PlaceOrderUseCase;
import com.fleet.storefront.application.port.in.UnknownProductException;
import com.fleet.storefront.application.port.out.FiscalSequence;
import com.fleet.storefront.application.port.out.IdempotencyStore;
import com.fleet.storefront.application.port.out.InventoryLedger;
import com.fleet.storefront.application.port.out.OrderRepository;
import com.fleet.storefront.application.port.out.Outbox;
import com.fleet.storefront.application.port.out.ProductCatalog;
import com.fleet.storefront.application.port.out.TransactionRunner;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductInactiveException;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.fiscal.FiscalAllocation;
import com.fleet.storefront.domain.fiscal.FiscalSeries;
import com.fleet.storefront.domain.inventory.StockChange;
import com.fleet.storefront.domain.inventory.StockChangeReason;
import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.OrderDraft;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.OrderLine;
import com.fleet.storefront.domain.order.OrderNumber;
import com.fleet.storefront.domain.order.Quantity;
import com.fleet.storefront.domain.order.event.KitchenTicketRequested;
import com.fleet.storefront.domain.order.event.OrderPlaced;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Checkout use case.
 *
 * <p>Everything happens in one local transaction whose locks are always acquired in the same order,
 * which rules out deadlocks between checkouts:
 * <ol>
 *   <li><b>idempotency key</b> — first, so duplicate submissions queue behind the original;</li>
 *   <li><b>inventory rows</b> — atomic conditional decrements (+ journal rows), sorted by product id;</li>
 *   <li><b>fiscal counter</b> — last, so the single hot row per branch/day is held only for the few
 *       inserts that follow, and a failed checkout never consumes a number.</li>
 * </ol>
 * Checkout never locks an existing order row; lifecycle use cases lock it between the idempotency key
 * and the inventory rows (global order: key → order row → inventory → fiscal counter).
 * Downstream notifications are written to the outbox in the same transaction and delivered later.
 */
public final class PlaceOrderService implements PlaceOrderUseCase {

    private final TransactionRunner transactions;
    private final IdempotencyStore idempotency;
    private final ProductCatalog catalog;
    private final InventoryLedger inventory;
    private final FiscalSequence fiscalSequence;
    private final OrderRepository orders;
    private final Outbox outbox;
    private final BranchContext branch;
    private final Clock clock;

    public PlaceOrderService(TransactionRunner transactions, IdempotencyStore idempotency, ProductCatalog catalog,
                             InventoryLedger inventory, FiscalSequence fiscalSequence, OrderRepository orders,
                             Outbox outbox, BranchContext branch, Clock clock) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.catalog = catalog;
        this.inventory = inventory;
        this.fiscalSequence = fiscalSequence;
        this.orders = orders;
        this.outbox = outbox;
        this.branch = branch;
        this.clock = clock;
    }

    @Override
    public PlaceOrderResult placeOrder(PlaceOrderCommand command) {
        String fingerprint = command.fingerprint();
        return transactions.inTransaction(() -> {
            // 1. Idempotency gate (blocks while a concurrent duplicate is in flight).
            IdempotencyStore.Claim claim =
                    idempotency.claim(command.terminalId(), command.idempotencyKey(), fingerprint, clock.instant());
            if (claim instanceof IdempotencyStore.AlreadyCompleted done) {
                return replay(command, fingerprint, done);
            }

            // Pure validation and pricing — no locks taken yet.
            OrderDraft draft = draft(command.mergedItems());
            OrderId orderId = OrderId.newId();

            // 2. Take stock (row locks in product-id order), journaled as a sale of this order.
            inventory.take(draft.stockRequirements(),
                    StockChange.of(StockChangeReason.SALE, orderId.value(), command.terminalId().value()));

            // 3. Fiscal number, as late as possible. The timestamp is taken while holding the counter
            //    lock and never runs backwards, so number order and timestamp order agree. Truncated to
            //    the storage precision so a replayed response is identical to the original one.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            LocalDate businessDate = LocalDate.ofInstant(now, branch.zone());
            FiscalAllocation number = fiscalSequence.next(FiscalSeries.ORD, branch.branchCode(), businessDate, now);
            Order order = draft.issue(orderId, new OrderNumber(branch.branchCode(), businessDate, number.sequence()),
                    command.terminalId(), number.issuedAt(), number.previousHash());
            fiscalSequence.recordHash(FiscalSeries.ORD, branch.branchCode(), businessDate, number.sequence(),
                    order.fiscalHash());

            orders.insert(order);
            idempotency.complete(command.terminalId(), command.idempotencyKey(), order.id().value());
            order.kitchenTicketRequest().ifPresent(ticket -> outbox.append(new Outbox.Message(
                    Outbox.Destination.KITCHEN, KitchenTicketRequested.TYPE, order.id().value(), ticket)));
            outbox.append(new Outbox.Message(
                    Outbox.Destination.HQ, OrderPlaced.TYPE, order.id().value(), order.placedEvent()));
            return PlaceOrderResult.created(OrderAccount.of(order));
        });
    }

    private PlaceOrderResult replay(PlaceOrderCommand command, String fingerprint, IdempotencyStore.AlreadyCompleted done) {
        if (!done.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException(command.idempotencyKey());
        }
        OrderId orderId = new OrderId(done.resourceId());
        OrderAccount original = orders.findAccount(orderId)
                .orElseThrow(() -> new IllegalStateException("Idempotency record points to missing order " + orderId));
        return PlaceOrderResult.replay(original);
    }

    private OrderDraft draft(SortedMap<Sku, Quantity> items) {
        Map<Sku, Product> products = catalog.findBySkus(items.keySet());
        List<Sku> unknown = items.keySet().stream().filter(sku -> !products.containsKey(sku)).toList();
        if (!unknown.isEmpty()) {
            throw new UnknownProductException(unknown);
        }
        List<Sku> inactive = items.keySet().stream().filter(sku -> !products.get(sku).active()).toList();
        if (!inactive.isEmpty()) {
            throw new ProductInactiveException(inactive);
        }
        List<OrderLine> lines = new ArrayList<>();
        items.forEach((sku, qty) -> lines.add(OrderLine.of(lines.size() + 1, products.get(sku), qty)));
        return new OrderDraft(branch.currency(), lines);
    }
}
