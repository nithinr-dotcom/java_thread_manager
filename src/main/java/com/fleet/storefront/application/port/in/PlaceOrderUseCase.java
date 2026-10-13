package com.fleet.storefront.application.port.in;

/**
 * Checkout: validates and prices the basket, takes stock, issues the fiscal number and queues the
 * downstream notifications — atomically. Retries with the same idempotency key replay the original
 * outcome without side effects.
 *
 * @see PlaceOrderCommand
 */
public interface PlaceOrderUseCase {

    PlaceOrderResult placeOrder(PlaceOrderCommand command);
}
