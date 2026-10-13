package com.fleet.storefront.application.port.out;

import com.fleet.storefront.application.readmodel.DeadLetterView;
import com.fleet.storefront.application.readmodel.OutboxDestinationStatus;

import java.util.List;
import java.util.UUID;

public interface OutboxMonitor {

    /** One entry per destination that has ever had events, in destination order. */
    List<OutboxDestinationStatus> status();

    /** Dead letters, newest first. */
    List<DeadLetterView> deadLetters(int limit);

    /** Re-queues a dead letter for immediate delivery with a fresh attempt budget; {@code false} if no such dead letter. */
    boolean requeue(UUID eventId);

    /** Re-queues every dead letter of {@code destination}; returns how many. */
    int requeueAll(String destination);
}
