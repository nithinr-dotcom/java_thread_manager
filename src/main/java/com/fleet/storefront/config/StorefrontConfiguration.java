package com.fleet.storefront.config;

import com.fleet.storefront.application.port.out.FiscalSequence;
import com.fleet.storefront.application.port.out.Housekeeping;
import com.fleet.storefront.application.port.out.HqCatalogFeed;
import com.fleet.storefront.application.port.out.IdempotencyStore;
import com.fleet.storefront.application.port.out.InventoryLedger;
import com.fleet.storefront.application.port.out.KitchenBoard;
import com.fleet.storefront.application.port.out.OrderRepository;
import com.fleet.storefront.application.port.out.Outbox;
import com.fleet.storefront.application.port.out.OutboxMonitor;
import com.fleet.storefront.application.port.out.ProductCatalog;
import com.fleet.storefront.application.port.out.ReportStore;
import com.fleet.storefront.application.port.out.TerminalRegistry;
import com.fleet.storefront.application.port.out.TransactionRunner;
import com.fleet.storefront.application.service.BranchContext;
import com.fleet.storefront.application.service.BranchInfoService;
import com.fleet.storefront.application.service.CancelOrderService;
import com.fleet.storefront.application.service.CatalogService;
import com.fleet.storefront.application.service.HousekeepingService;
import com.fleet.storefront.application.service.InventoryService;
import com.fleet.storefront.application.service.KitchenBoardService;
import com.fleet.storefront.application.service.OrderQueryService;
import com.fleet.storefront.application.service.OutboxStatusService;
import com.fleet.storefront.application.service.PayOrderService;
import com.fleet.storefront.application.service.PlaceOrderService;
import com.fleet.storefront.application.service.RefundOrderService;
import com.fleet.storefront.application.service.ReportService;
import com.fleet.storefront.application.service.TerminalService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Currency;

/**
 * Composition root: the only place that knows both the framework-free use cases and the adapters
 * implementing their output ports. Each bean is exposed to the inbound adapters through its input-port
 * interface(s) ({@code application.port.in}), never as the concrete service.
 */
@Configuration(proxyBeanMethods = false)
public class StorefrontConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BranchContext branchContext(StorefrontProperties properties) {
        StorefrontProperties.Branch branch = properties.branch();
        return new BranchContext(branch.code(), Currency.getInstance(branch.currency()), ZoneId.of(branch.zone()));
    }

    @Bean
    PlaceOrderService placeOrderService(TransactionRunner transactions, IdempotencyStore idempotency,
                                        ProductCatalog catalog, InventoryLedger inventory,
                                        FiscalSequence fiscalSequence, OrderRepository orders, Outbox outbox,
                                        BranchContext branch, Clock clock) {
        return new PlaceOrderService(transactions, idempotency, catalog, inventory, fiscalSequence, orders,
                outbox, branch, clock);
    }

    @Bean
    PayOrderService payOrderService(TransactionRunner transactions, IdempotencyStore idempotency,
                                    OrderRepository orders, Outbox outbox, Clock clock) {
        return new PayOrderService(transactions, idempotency, orders, outbox, clock);
    }

    @Bean
    CancelOrderService cancelOrderService(TransactionRunner transactions, IdempotencyStore idempotency,
                                          OrderRepository orders, InventoryLedger inventory, Outbox outbox,
                                          Clock clock) {
        return new CancelOrderService(transactions, idempotency, orders, inventory, outbox, clock);
    }

    @Bean
    RefundOrderService refundOrderService(TransactionRunner transactions, IdempotencyStore idempotency,
                                          OrderRepository orders, InventoryLedger inventory,
                                          FiscalSequence fiscalSequence, Outbox outbox, BranchContext branch,
                                          Clock clock) {
        return new RefundOrderService(transactions, idempotency, orders, inventory, fiscalSequence, outbox, branch,
                clock);
    }

    @Bean
    OrderQueryService orderQueryService(OrderRepository orders) {
        return new OrderQueryService(orders);
    }

    @Bean
    CatalogService catalogService(ProductCatalog catalog, InventoryLedger inventory, TransactionRunner transactions,
                                  HqCatalogFeed hqFeed, BranchContext branch, Clock clock) {
        return new CatalogService(catalog, inventory, transactions, hqFeed, branch, clock);
    }

    @Bean
    InventoryService inventoryService(ProductCatalog catalog, InventoryLedger inventory, IdempotencyStore idempotency,
                                      TransactionRunner transactions, Clock clock) {
        return new InventoryService(catalog, inventory, idempotency, transactions, clock);
    }

    @Bean
    KitchenBoardService kitchenBoardService(KitchenBoard board) {
        return new KitchenBoardService(board);
    }

    @Bean
    OutboxStatusService outboxStatusService(OutboxMonitor monitor) {
        return new OutboxStatusService(monitor);
    }

    @Bean
    TerminalService terminalService(TerminalRegistry registry, TransactionRunner transactions, Clock clock) {
        return new TerminalService(registry, transactions, clock);
    }

    @Bean
    BranchInfoService branchInfoService(BranchContext branch) {
        return new BranchInfoService(branch);
    }

    @Bean
    ReportService reportService(ReportStore reports, OrderRepository orders, FiscalSequence fiscalSequence,
                                BranchContext branch, Clock clock) {
        return new ReportService(reports, orders, fiscalSequence, branch, clock);
    }

    @Bean
    HousekeepingService housekeepingService(Housekeeping housekeeping, Clock clock, StorefrontProperties properties) {
        StorefrontProperties.Retention retention = properties.retention();
        return new HousekeepingService(housekeeping, clock, retention.idempotencyKeys(), retention.outboxEvents(),
                retention.batchSize());
    }
}
