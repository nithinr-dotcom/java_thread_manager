package com.fleet.storefront.application.port.out;

import com.fleet.storefront.application.readmodel.KitchenTicketView;
import com.fleet.storefront.domain.kitchen.KitchenTicketStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KitchenBoard {

    /** Most recent first; optionally only active tickets and/or tickets with an item for {@code station}. */
    List<KitchenTicketView> recent(int limit, boolean activeOnly, Optional<String> station);

    Optional<KitchenTicketView> find(UUID ticketId);

    /** Conditional transition; {@code false} if the ticket is no longer in {@code from} (concurrent change). */
    boolean transition(UUID ticketId, KitchenTicketStatus from, KitchenTicketStatus to);

    /** Tickets created or changed after {@code since}, oldest change first. */
    List<KitchenTicketView> changedSince(Instant since, Optional<String> station);

    /** The database clock (the clock that stamps {@code updatedAt}). */
    Instant now();
}
