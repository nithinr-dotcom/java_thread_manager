package com.fleet.storefront.domain.kitchen;

import java.util.Map;
import java.util.Set;

/**
 * Kitchen ticket workflow: {@code QUEUED → IN_PROGRESS → READY → SERVED}, with skip-ahead
 * ({@code QUEUED → READY}) and recall ({@code READY → IN_PROGRESS}). {@code CANCELLED} is only reached
 * when the order is cancelled, never by a station.
 */
public enum KitchenTicketStatus {
    QUEUED, IN_PROGRESS, READY, SERVED, CANCELLED;

    private static final Map<KitchenTicketStatus, Set<KitchenTicketStatus>> MANUAL = Map.of(
            QUEUED, Set.of(IN_PROGRESS, READY),
            IN_PROGRESS, Set.of(READY),
            READY, Set.of(SERVED, IN_PROGRESS),
            SERVED, Set.of(),
            CANCELLED, Set.of());

    public boolean isActive() {
        return this == QUEUED || this == IN_PROGRESS || this == READY;
    }

    public boolean canMoveTo(KitchenTicketStatus target) {
        return MANUAL.get(this).contains(target);
    }

    /** @throws TicketStateConflictException if a station may not move a ticket from this status to {@code target} */
    public void requireTransitionTo(KitchenTicketStatus target) {
        if (!canMoveTo(target)) {
            throw new TicketStateConflictException("Ticket cannot move from " + this + " to " + target);
        }
    }
}
