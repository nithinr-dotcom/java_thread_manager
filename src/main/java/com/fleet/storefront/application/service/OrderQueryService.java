package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.FindOrdersQuery;
import com.fleet.storefront.application.port.in.Limit;
import com.fleet.storefront.application.port.out.OrderRepository;
import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.OrderStatus;

import java.util.List;
import java.util.Optional;

public final class OrderQueryService implements FindOrdersQuery {

    private final OrderRepository orders;

    public OrderQueryService(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Optional<OrderAccount> byId(OrderId id) {
        return orders.findAccount(id);
    }

    @Override
    public List<OrderAccount> recent(Limit limit, Optional<OrderStatus> status) {
        return orders.findRecentAccounts(limit.value(), status);
    }
}
