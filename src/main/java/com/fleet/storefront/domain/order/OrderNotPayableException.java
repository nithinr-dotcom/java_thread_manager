package com.fleet.storefront.domain.order;

import com.fleet.storefront.domain.shared.DomainException;

public final class OrderNotPayableException extends DomainException {

    public OrderNotPayableException(OrderNumber number, OrderStatus status) {
        super("ORDER_NOT_PAYABLE", "Order " + number + " is " + status + ": only orders awaiting payment can be paid");
    }
}
