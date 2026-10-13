package com.fleet.storefront.domain.order;

import com.fleet.storefront.domain.shared.DomainException;

public final class OrderNotRefundableException extends DomainException {

    public OrderNotRefundableException(OrderNumber number, OrderStatus status) {
        super("ORDER_NOT_REFUNDABLE", "Order " + number + " is " + status + ": only paid orders can be refunded");
    }
}
