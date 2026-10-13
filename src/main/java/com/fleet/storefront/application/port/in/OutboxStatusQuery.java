package com.fleet.storefront.application.port.in;

import com.fleet.storefront.application.readmodel.DeadLetterView;
import com.fleet.storefront.application.readmodel.OutboxDestinationStatus;

import java.util.List;
import java.util.UUID;

/** Delivery backlog per downstream destination and dead-letter handling, for operators. */
public interface OutboxStatusQuery {

    List<OutboxDestinationStatus> outboxStatus();

    List<DeadLetterView> deadLetters(Limit limit);

    /** @throws DeadLetterNotFoundException if {@code eventId} is not a dead letter */
    void retryDeadLetter(UUID eventId);

    /** Re-queues every dead letter of a destination (e.g. after fixing HQ); returns how many. */
    int retryDeadLetters(String destination);
}
