package com.fleet.storefront.it;

import com.fleet.storefront.application.port.in.RestockUseCase;
import com.fleet.storefront.application.port.in.RestockUseCase.RestockCommand;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.inventory.InsufficientStockException;
import com.fleet.storefront.domain.order.TerminalId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** Restocks racing checkouts on the same row: increments are never lost and stock never goes negative. */
class RestockConcurrencyIT extends IntegrationTestSupport {

    @Autowired
    RestockUseCase restock;

    @Test
    void restocksInterleavedWithAStockRace_conserveEveryUnit() throws Exception {
        givenProduct("MEALBOX", "8.95", false, 10);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String terminal = "pos-" + i;
            tasks.add(() -> {
                placeOrder.placeOrder(order(terminal, "k", "MEALBOX", 1));
                return true;
            });
        }
        for (int i = 0; i < 20; i++) {
            String key = "restock-" + i;
            tasks.add(() -> {
                restock.restock(new RestockCommand(Sku.of("MEALBOX"), 1, null, new TerminalId("mgr-1"), key));
                return false;
            });
        }
        List<Outcome<Boolean>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(o -> !o.ok()).allMatch(o -> o.error() instanceof InsufficientStockException);
        long sold = outcomes.stream().filter(o -> o.ok() && o.value()).count();
        long restocked = outcomes.stream().filter(o -> o.ok() && !o.value()).count();

        assertThat(restocked).isEqualTo(20);
        assertThat(sold).isBetween(10L, 30L);
        assertThat(count("customer_order")).isEqualTo(sold);
        assertThat(stockOf("MEALBOX")).isEqualTo(10 + restocked - sold).isNotNegative();
    }
}
