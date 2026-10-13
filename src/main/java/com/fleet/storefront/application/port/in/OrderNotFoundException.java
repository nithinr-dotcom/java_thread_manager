package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.shared.DomainException;

public final class OrderNotFoundException extends DomainException {

    public OrderNotFoundException(OrderId id) {
        super("ORDER_NOT_FOUND", "No order with id " + id);
    }
}
