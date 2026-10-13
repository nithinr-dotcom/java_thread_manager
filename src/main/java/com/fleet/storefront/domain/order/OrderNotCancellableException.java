package com.fleet.storefront.domain.order;

import com.fleet.storefront.domain.shared.DomainException;

public final class OrderNotCancellableException extends DomainException {

    public OrderNotCancellableException(OrderNumber number, OrderStatus status) {
        super("ORDER_NOT_CANCELLABLE", "Order " + number + " is " + status + ": only unpaid orders awaiting payment can be cancelled");
    }
}
