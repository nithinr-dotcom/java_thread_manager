package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderAccount;

/**
 * @param replayed {@code true} when the request was a retry of an already committed checkout and
 *                 the order's current state is returned without any side effects
 */
public record PlaceOrderResult(OrderAccount account, boolean replayed) {

    public static PlaceOrderResult created(OrderAccount account) {
        return new PlaceOrderResult(account, false);
    }

    public static PlaceOrderResult replay(OrderAccount account) {
        return new PlaceOrderResult(account, true);
    }

    public Order order() {
        return account.order();
    }
}
