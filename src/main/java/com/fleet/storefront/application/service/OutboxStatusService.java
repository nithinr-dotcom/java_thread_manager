package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.DeadLetterNotFoundException;
import com.fleet.storefront.application.port.in.Limit;
import com.fleet.storefront.application.port.in.OutboxStatusQuery;
import com.fleet.storefront.application.port.out.OutboxMonitor;
import com.fleet.storefront.application.readmodel.DeadLetterView;
import com.fleet.storefront.application.readmodel.OutboxDestinationStatus;

import java.util.List;
import java.util.UUID;

public final class OutboxStatusService implements OutboxStatusQuery {

    private final OutboxMonitor monitor;

    public OutboxStatusService(OutboxMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public List<OutboxDestinationStatus> outboxStatus() {
        return monitor.status();
    }

    @Override
    public List<DeadLetterView> deadLetters(Limit limit) {
        return monitor.deadLetters(limit.value());
    }

    @Override
    public void retryDeadLetter(UUID eventId) {
        if (!monitor.requeue(eventId)) {
            throw new DeadLetterNotFoundException(eventId);
        }
    }

    @Override
    public int retryDeadLetters(String destination) {
        return monitor.requeueAll(destination);
    }
}
