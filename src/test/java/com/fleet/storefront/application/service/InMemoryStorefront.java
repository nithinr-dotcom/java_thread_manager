package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.out.FiscalSequence;
import com.fleet.storefront.application.port.out.HqCatalogFeed;
import com.fleet.storefront.application.port.out.IdempotencyStore;
import com.fleet.storefront.application.port.out.InventoryLedger;
import com.fleet.storefront.application.port.out.OrderRepository;
import com.fleet.storefront.application.port.out.Outbox;
import com.fleet.storefront.application.port.out.ProductCatalog;
import com.fleet.storefront.application.port.out.TransactionRunner;
import com.fleet.storefront.application.readmodel.StockMovementView;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.fiscal.FiscalAllocation;
import com.fleet.storefront.domain.fiscal.FiscalCounterState;
import com.fleet.storefront.domain.fiscal.FiscalSeries;
import com.fleet.storefront.domain.inventory.InsufficientStockException;
import com.fleet.storefront.domain.inventory.StockChange;
import com.fleet.storefront.domain.inventory.StockRequirement;
import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.OrderStatus;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.payment.Payment;
import com.fleet.storefront.domain.refund.Refund;
import com.fleet.storefront.domain.shared.UuidV7;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Single-threaded in-memory implementation of the use-case ports, with snapshot-based rollback, so the
 * use cases' transactional behaviour can be unit tested without Spring or a database. Every port call
 * that would take a lock or write is appended to {@link #calls}, so tests can assert the lock order.
 */
final class InMemoryStorefront implements TransactionRunner, IdempotencyStore, ProductCatalog, InventoryLedger,
        FiscalSequence, OrderRepository, Outbox, HqCatalogFeed {

    record Counter(long value, Instant issuedAt, String hash, Long firstHashed) {
    }

    record State(Map<ProductId, Integer> stock, Map<String, Counter> counters, Map<OrderId, Order> orders,
                 Map<OrderId, OrderStatus> statuses, Map<OrderId, List<Payment>> payments,
                 Map<OrderId, List<Refund>> refunds, Map<String, String[]> keys, List<Message> outbox,
                 List<StockMovementView> movements, Map<Sku, Product> products) {
        State copy() {
            Map<String, String[]> keysCopy = new HashMap<>();
            keys.forEach((k, v) -> keysCopy.put(k, v.clone()));
            Map<OrderId, List<Payment>> paymentsCopy = new HashMap<>();
            payments.forEach((k, v) -> paymentsCopy.put(k, new ArrayList<>(v)));
            Map<OrderId, List<Refund>> refundsCopy = new HashMap<>();
            refunds.forEach((k, v) -> refundsCopy.put(k, new ArrayList<>(v)));
            return new State(new HashMap<>(stock), new HashMap<>(counters), new HashMap<>(orders),
                    new HashMap<>(statuses), paymentsCopy, refundsCopy, keysCopy, new ArrayList<>(outbox),
                    new ArrayList<>(movements), new HashMap<>(products));
        }
    }

    /** Port calls in invocation order, e.g. {@code "claim"}, {@code "take:WATER"}, {@code "fiscal:ORD"}. Never rolled back. */
    final List<String> calls = new ArrayList<>();
    final List<Product> hqFeed = new ArrayList<>();
    long hqWatermark;
    State state = new State(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
            new HashMap<>(), new HashMap<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>());

    void add(Product product, int onHand) {
        state.products().put(product.sku(), product);
        state.stock().put(product.id(), onHand);
    }

    int stockOf(Product product) {
        return state.stock().get(product.id());
    }

    long lastIssued() {
        return lastIssued(FiscalSeries.ORD);
    }

    long lastIssued(FiscalSeries series) {
        return state.counters().entrySet().stream()
                .filter(e -> e.getKey().startsWith(series + "/"))
                .mapToLong(e -> e.getValue().value())
                .max().orElse(0);
    }

    // --- TransactionRunner --------------------------------------------------------------------

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        State before = state.copy();
        try {
            return work.get();
        } catch (RuntimeException e) {
            state = before;
            throw e;
        }
    }

    // --- IdempotencyStore ---------------------------------------------------------------------

    @Override
    public Claim claim(TerminalId terminalId, String key, String fingerprint, Instant now) {
        calls.add("claim");
        String[] existing = state.keys().get(terminalId + "/" + key);
        if (existing == null) {
            state.keys().put(terminalId + "/" + key, new String[]{fingerprint, null});
            return new Claimed();
        }
        return new AlreadyCompleted(UUID.fromString(existing[1]), existing[0]);
    }

    @Override
    public void complete(TerminalId terminalId, String key, UUID resourceId) {
        calls.add("complete");
        state.keys().get(terminalId + "/" + key)[1] = resourceId.toString();
    }

    // --- ProductCatalog -----------------------------------------------------------------------

    @Override
    public Map<Sku, Product> findBySkus(Collection<Sku> skus) {
        Map<Sku, Product> found = new HashMap<>();
        skus.stream().filter(state.products()::containsKey).forEach(s -> found.put(s, state.products().get(s)));
        return found;
    }

    @Override
    public Optional<Product> findBySku(Sku sku) {
        return Optional.ofNullable(state.products().get(sku));
    }

    @Override
    public List<Product> findAll(boolean includeInactive) {
        return state.products().values().stream()
                .filter(p -> includeInactive || p.active())
                .sorted(Comparator.comparing(Product::sku))
                .toList();
    }

    @Override
    public void insert(Product product) {
        calls.add("product:insert");
        state.products().put(product.sku(), product);
    }

    @Override
    public boolean update(Product product, long expectedVersion) {
        calls.add("product:update");
        Product current = state.products().get(product.sku());
        if (current == null || current.version() != expectedVersion) {
            return false;
        }
        state.products().put(product.sku(), product);
        return true;
    }

    @Override
    public boolean upsertIfNewer(Product product) {
        Optional<Product> current = state.products().values().stream()
                .filter(p -> p.id().equals(product.id())).findFirst();
        if (current.isPresent() && current.get().version() >= product.version()) {
            return false;
        }
        current.ifPresent(c -> state.products().remove(c.sku()));
        state.products().put(product.sku(), product);
        return true;
    }

    @Override
    public long hqCatalogWatermark() {
        return hqWatermark;
    }

    @Override
    public void saveHqCatalogWatermark(long version) {
        hqWatermark = Math.max(hqWatermark, version);
    }

    // --- HqCatalogFeed ------------------------------------------------------------------------

    @Override
    public List<Product> changesSince(String branchCode, long sinceVersion) {
        return hqFeed.stream().filter(p -> p.version() > sinceVersion).toList();
    }

    // --- InventoryLedger ----------------------------------------------------------------------

    @Override
    public void take(List<StockRequirement> requirements, StockChange change) {
        for (StockRequirement r : requirements) {
            calls.add("take:" + r.sku());
            int onHand = state.stock().getOrDefault(r.productId(), 0);
            if (onHand < r.quantity().value()) {
                throw new InsufficientStockException(r.sku(), r.quantity().value());
            }
            state.stock().put(r.productId(), onHand - r.quantity().value());
            journal(r.productId(), r.sku().value(), -r.quantity().value(), change);
        }
    }

    @Override
    public int add(ProductId productId, int quantity, StockChange change) {
        String sku = skuOf(productId);
        calls.add("add:" + sku);
        int onHand = state.stock().merge(productId, quantity, Integer::sum);
        journal(productId, sku, quantity, change);
        return onHand;
    }

    @Override
    public int remove(ProductId productId, Sku sku, int quantity, StockChange change) {
        calls.add("remove:" + sku);
        int onHand = state.stock().getOrDefault(productId, 0);
        if (onHand < quantity) {
            throw new InsufficientStockException(sku, quantity);
        }
        state.stock().put(productId, onHand - quantity);
        journal(productId, sku.value(), -quantity, change);
        return onHand - quantity;
    }

    private void journal(ProductId productId, String sku, int delta, StockChange change) {
        state.movements().add(new StockMovementView(UuidV7.next(), sku, delta, change.reason().name(),
                change.referenceId(), change.terminalId(), change.note(), state.stock().get(productId), Instant.now()));
    }

    private String skuOf(ProductId productId) {
        return state.products().values().stream().filter(p -> p.id().equals(productId))
                .map(p -> p.sku().value()).findFirst().orElse(productId.toString());
    }

    @Override
    public Map<ProductId, Integer> onHand(Collection<ProductId> productIds) {
        Map<ProductId, Integer> found = new HashMap<>();
        productIds.stream().filter(state.stock()::containsKey).forEach(id -> found.put(id, state.stock().get(id)));
        return found;
    }

    @Override
    public List<StockMovementView> movements(ProductId productId, Sku sku, int limit) {
        return state.movements().stream().filter(m -> m.sku().equals(sku.value())).toList().reversed().stream()
                .limit(limit).toList();
    }

    // --- FiscalSequence -----------------------------------------------------------------------

    @Override
    public FiscalAllocation next(FiscalSeries series, String branchCode, LocalDate businessDate, Instant now) {
        calls.add("fiscal:" + series);
        String key = series + "/" + branchCode + "/" + businessDate;
        Counter current = state.counters().get(key);
        Counter next = current == null
                ? new Counter(1, now, null, null)
                : new Counter(current.value() + 1, current.issuedAt().isAfter(now) ? current.issuedAt() : now,
                current.hash(), current.firstHashed());
        state.counters().put(key, next);
        return new FiscalAllocation(next.value(), next.issuedAt(), next.hash());
    }

    @Override
    public void recordHash(FiscalSeries series, String branchCode, LocalDate businessDate, long sequence, String hash) {
        calls.add("fiscal-hash:" + series);
        String key = series + "/" + branchCode + "/" + businessDate;
        Counter c = state.counters().get(key);
        state.counters().put(key, new Counter(c.value(), c.issuedAt(), hash,
                c.firstHashed() == null ? sequence : c.firstHashed()));
    }

    @Override
    public Optional<FiscalCounterState> state(FiscalSeries series, String branchCode, LocalDate businessDate) {
        return Optional.ofNullable(state.counters().get(series + "/" + branchCode + "/" + businessDate))
                .map(c -> new FiscalCounterState(c.value(), c.hash(), c.firstHashed()));
    }

    // --- OrderRepository ----------------------------------------------------------------------

    @Override
    public void insert(Order order) {
        calls.add("insert");
        state.orders().put(order.id(), order);
        state.statuses().put(order.id(), order.status());
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        Order stored = state.orders().get(id);
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(Order.restore(stored.id(), stored.number(), stored.terminalId(), stored.currency(),
                stored.lines(), state.statuses().get(id), stored.placedAt(), stored.fiscalHash()));
    }

    @Override
    public Optional<OrderAccount> findAccount(OrderId id) {
        return findById(id).map(o -> new OrderAccount(o, state.payments().getOrDefault(id, List.of()),
                state.refunds().getOrDefault(id, List.of())));
    }

    @Override
    public Optional<OrderAccount> lockAccount(OrderId id) {
        calls.add("lock-order");
        return findAccount(id);
    }

    @Override
    public List<OrderAccount> findRecentAccounts(int limit, Optional<OrderStatus> status) {
        return state.orders().keySet().stream()
                .map(id -> findAccount(id).orElseThrow())
                .filter(a -> status.isEmpty() || a.status() == status.get())
                .sorted(Comparator.comparing((OrderAccount a) -> a.order().placedAt())
                        .thenComparing(a -> a.order().number()).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public void updateStatus(OrderId id, OrderStatus status, String reason) {
        calls.add("status:" + status);
        state.statuses().put(id, status);
    }

    @Override
    public void insertPayment(Payment payment) {
        calls.add("insert-payment");
        state.payments().computeIfAbsent(payment.orderId(), k -> new ArrayList<>()).add(payment);
    }

    @Override
    public void voidPayment(Payment voided) {
        calls.add("void-payment");
        state.payments().get(voided.orderId()).replaceAll(p -> p.id().equals(voided.id()) ? voided : p);
    }

    @Override
    public void insertRefund(Refund refund) {
        calls.add("insert-refund");
        state.refunds().computeIfAbsent(refund.orderId(), k -> new ArrayList<>()).add(refund);
    }

    @Override
    public List<Order> issuedOn(String branchCode, LocalDate businessDate) {
        return state.orders().keySet().stream().map(id -> findById(id).orElseThrow())
                .filter(o -> o.number().branchCode().equals(branchCode) && o.number().businessDate().equals(businessDate))
                .sorted(Comparator.comparing(Order::number))
                .toList();
    }

    @Override
    public List<Refund> refundsIssuedOn(String branchCode, LocalDate businessDate) {
        return state.refunds().values().stream().flatMap(List::stream)
                .filter(r -> r.number().branchCode().equals(branchCode) && r.number().businessDate().equals(businessDate))
                .sorted(Comparator.comparingLong(r -> r.number().sequence()))
                .toList();
    }

    // --- Outbox -------------------------------------------------------------------------------

    @Override
    public void append(Message message) {
        calls.add("outbox:" + message.destination());
        state.outbox().add(message);
    }
}
