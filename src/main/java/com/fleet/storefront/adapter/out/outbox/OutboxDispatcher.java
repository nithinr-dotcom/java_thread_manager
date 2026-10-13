package com.fleet.storefront.adapter.out.outbox;

/**
 * Delivers outbox records of one destination. Delivery is at-least-once, so implementations must be
 * idempotent (e.g. keyed by {@link OutboxRecord#id()} or the aggregate id).
 */
public interface OutboxDispatcher {

    String destination();

    void dispatch(OutboxRecord record) throws Exception;
}
