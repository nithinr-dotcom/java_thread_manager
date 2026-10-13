package com.fleet.storefront.application.port.out;

import java.util.Objects;
import java.util.UUID;

/**
 * Transactional outbox: messages appended here commit or roll back together with the business data
 * and are delivered asynchronously (at-least-once) by a relay.
 */
public interface Outbox {

    void append(Message message);

    enum Destination { KITCHEN, HQ }

    record Message(Destination destination, String eventType, UUID aggregateId, Object payload) {
        public Message {
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(aggregateId, "aggregateId");
            Objects.requireNonNull(payload, "payload");
        }
    }
}
