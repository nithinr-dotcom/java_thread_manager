package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.OrderStatus;

import java.util.List;
import java.util.Optional;

public interface FindOrdersQuery {

    Optional<OrderAccount> byId(OrderId id);

    /** Most recently placed first, optionally only orders in {@code status}. */
    List<OrderAccount> recent(Limit limit, Optional<OrderStatus> status);
}
