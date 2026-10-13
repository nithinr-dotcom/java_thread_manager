package com.fleet.storefront.application.port.in;

import com.fleet.storefront.application.readmodel.KitchenTicketView;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** What the Kitchen Display System shows. */
public interface KitchenBoardQuery {

    /** Most recent first. */
    List<KitchenTicketView> tickets(Limit limit, boolean activeOnly, Optional<String> station);

    /** Tickets created or changed after {@code since}, oldest change first (drives the live stream). */
    List<KitchenTicketView> changedSince(Instant since, Optional<String> station);

    /** The clock that stamps ticket changes; stream cursors must use it, not the JVM clock. */
    Instant now();
}
