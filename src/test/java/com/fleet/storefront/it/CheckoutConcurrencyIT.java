package com.fleet.storefront.it;

import com.fleet.storefront.application.port.in.IdempotencyKeyReusedException;
import com.fleet.storefront.application.port.in.PlaceOrderResult;
import com.fleet.storefront.domain.inventory.InsufficientStockException;
import com.fleet.storefront.domain.order.OrderNumber;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scenario A (contention), B (fiscal numbering) and C (retries/duplicates) against real PostgreSQL. */
class CheckoutConcurrencyIT extends IntegrationTestSupport {

    @Test
    void fiftyTerminalsRaceForTenUnits_exactlyTenSucceedAndNumbersAreGapFreeAndOrdered() throws Exception {
        givenProduct("MEALBOX", "8.95", false, 10);

        List<Callable<PlaceOrderResult>> tasks = IntStream.range(0, 50)
                .<Callable<PlaceOrderResult>>mapToObj(i -> () -> placeOrder.placeOrder(order("pos-" + i, "key-" + i, "MEALBOX", 1)))
                .toList();
        List<Outcome<PlaceOrderResult>> outcomes = runConcurrently(tasks);

        List<PlaceOrderResult> successes = outcomes.stream().filter(Outcome::ok).map(Outcome::value).toList();
        List<Throwable> failures = outcomes.stream().filter(o -> !o.ok()).map(Outcome::error).toList();

        // Zero overselling: exactly 10 winners, 40 clean domain failures, nothing else.
        assertThat(successes).hasSize(10);
        assertThat(failures).hasSize(40).allMatch(e -> e instanceof InsufficientStockException);
        assertThat(stockOf("MEALBOX")).isZero();
        assertThat(count("customer_order")).isEqualTo(10);

        // Unique and gap-free: 1..10 exactly.
        List<Long> sequences = successes.stream().map(r -> r.order().number().sequence()).sorted().toList();
        assertThat(sequences).containsExactlyElementsOf(LongStream.rangeClosed(1, 10).boxed().toList());
        assertThat(successes).extracting(r -> r.order().number().value()).doesNotHaveDuplicates();

        // Chronological: ordering by number gives non-decreasing placement timestamps (as persisted).
        List<Instant> placedAtInNumberOrder = jdbc.sql("SELECT placed_at FROM customer_order ORDER BY sequence_no")
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).list();
        assertThat(placedAtInNumberOrder).isSorted();
    }

    @Test
    void fiftyIdenticalSubmissionsWithinMilliseconds_produceExactlyOneOrder() throws Exception {
        givenProduct("BURGER", "11.40", true, 100);

        List<Callable<PlaceOrderResult>> tasks = IntStream.range(0, 50)
                .<Callable<PlaceOrderResult>>mapToObj(i -> () -> placeOrder.placeOrder(order("kiosk-7", "checkout-abc", "BURGER", 2)))
                .toList();
        List<Outcome<PlaceOrderResult>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::ok);
        Set<UUID> orderIds = outcomes.stream().map(o -> o.value().order().id().value()).collect(Collectors.toSet());
        assertThat(orderIds).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.value().replayed()).count()).isEqualTo(1);
        assertThat(outcomes).allMatch(o -> o.value().order().number().value().equals("ORD-HOSP01-"
                + o.value().order().number().businessDate().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-0001"));

        // Stock decremented once, one fiscal number consumed, one kitchen ticket queued.
        assertThat(stockOf("BURGER")).isEqualTo(98);
        assertThat(jdbc.sql("SELECT max(last_value) FROM fiscal_sequence").query(Long.class).single()).isEqualTo(1);
        assertThat(count("customer_order")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE destination = 'KITCHEN'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void mixedRetriesUnderContention_eachLogicalCheckoutMapsToAtMostOneOrder() throws Exception {
        givenProduct("WATER", "2.50", false, 20);

        // 40 logical checkouts, each sent twice at the same moment (original + network retry) = 80 calls.
        List<Callable<PlaceOrderResult>> tasks = new ArrayList<>();
        List<String> checkoutOfTask = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String terminal = "tablet-" + (i % 10);
            String key = "co-" + i;
            for (int attempt = 0; attempt < 2; attempt++) {
                tasks.add(() -> placeOrder.placeOrder(order(terminal, key, "WATER", 1)));
                checkoutOfTask.add(terminal + "/" + key);
            }
        }
        List<Outcome<PlaceOrderResult>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(o -> !o.ok())
                .allMatch(o -> o.error() instanceof InsufficientStockException);

        Map<String, Set<UUID>> ordersPerCheckout = IntStream.range(0, tasks.size())
                .filter(i -> outcomes.get(i).ok())
                .boxed()
                .collect(Collectors.groupingBy(checkoutOfTask::get,
                        Collectors.mapping(i -> outcomes.get(i).value().order().id().value(), Collectors.toSet())));
        assertThat(ordersPerCheckout.values()).allMatch(ids -> ids.size() == 1);
        assertThat(ordersPerCheckout).hasSize(20);

        long distinctOrders = outcomes.stream().filter(Outcome::ok).map(o -> o.value().order().id()).distinct().count();
        assertThat(distinctOrders).isEqualTo(20);
        assertThat(count("customer_order")).isEqualTo(20);
        assertThat(stockOf("WATER")).isZero();

        List<Long> sequences = jdbc.sql("SELECT sequence_no FROM customer_order ORDER BY sequence_no").query(Long.class).list();
        assertThat(sequences).containsExactlyElementsOf(LongStream.rangeClosed(1, 20).boxed().toList());

        // Every idempotency key maps to at most one order.
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_key").query(Long.class).single()).isEqualTo(20);
    }

    @Test
    void multiLineOrdersLockingInOppositeOrder_neverDeadlockAndKeepStockConsistent() throws Exception {
        givenProduct("ALPHA", "1.00", false, 1_000);
        givenProduct("BETA", "1.00", false, 1_000);

        List<Callable<PlaceOrderResult>> tasks = IntStream.range(0, 50)
                .<Callable<PlaceOrderResult>>mapToObj(i -> i % 2 == 0
                        ? () -> placeOrder.placeOrder(order("pos-" + i, "k-" + i, "ALPHA", 3, "BETA", 2))
                        : () -> placeOrder.placeOrder(order("pos-" + i, "k-" + i, "BETA", 2, "ALPHA", 3)))
                .toList();
        List<Outcome<PlaceOrderResult>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::ok);
        assertThat(stockOf("ALPHA")).isEqualTo(1_000 - 50 * 3);
        assertThat(stockOf("BETA")).isEqualTo(1_000 - 50 * 2);
    }

    @Test
    void retryAfterCommit_returnsCommittedOutcomeWithoutConsumingStockOrNumbers() {
        givenProduct("LATTE", "4.75", true, 5);

        PlaceOrderResult first = placeOrder.placeOrder(order("pos-1", "abc", "LATTE", 1));
        // Client timed out and retries the very same request.
        PlaceOrderResult retry = placeOrder.placeOrder(order("pos-1", "abc", "LATTE", 1));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.order().id()).isEqualTo(first.order().id());
        assertThat(retry.order().number()).isEqualTo(first.order().number());
        assertThat(retry.order().total()).isEqualTo(first.order().total());
        assertThat(stockOf("LATTE")).isEqualTo(4);

        // The next genuine checkout gets the very next number: the retry consumed none.
        PlaceOrderResult next = placeOrder.placeOrder(order("pos-1", "def", "LATTE", 1));
        assertThat(next.order().number().sequence()).isEqualTo(first.order().number().sequence() + 1);
    }

    @Test
    void reusingAKeyForADifferentBasket_isRejectedWithoutSideEffects() {
        givenProduct("LATTE", "4.75", true, 5);
        placeOrder.placeOrder(order("pos-1", "abc", "LATTE", 1));

        assertThatThrownBy(() -> placeOrder.placeOrder(order("pos-1", "abc", "LATTE", 2)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(stockOf("LATTE")).isEqualTo(4);
        assertThat(count("customer_order")).isEqualTo(1);
    }

    @Test
    void persistedOrderRoundTripsWithExactMoney() {
        givenProduct("MEALBOX", "8.95", false, 10);
        PlaceOrderResult result = placeOrder.placeOrder(order("pos-1", "k", "MEALBOX", 3));

        Map<String, Object> row = jdbc.sql("SELECT subtotal, tax_total, grand_total, order_number FROM customer_order")
                .query().singleRow();
        assertThat(row.get("subtotal").toString()).isEqualTo("26.8500");
        assertThat(row.get("tax_total").toString()).isEqualTo("2.2200");
        assertThat(row.get("grand_total").toString()).isEqualTo("29.0700");
        assertThat(OrderNumber.parse((String) row.get("order_number"))).isEqualTo(result.order().number());
    }
}
