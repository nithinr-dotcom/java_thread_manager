package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.AdvanceKitchenTicketUseCase;
import com.fleet.storefront.application.port.in.KitchenBoardQuery;
import com.fleet.storefront.application.port.in.Limit;
import com.fleet.storefront.application.port.in.TicketNotFoundException;
import com.fleet.storefront.application.port.out.KitchenBoard;
import com.fleet.storefront.application.readmodel.KitchenTicketView;
import com.fleet.storefront.domain.kitchen.KitchenTicketStatus;
import com.fleet.storefront.domain.kitchen.TicketStateConflictException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class KitchenBoardService implements KitchenBoardQuery, AdvanceKitchenTicketUseCase {

    private final KitchenBoard board;

    public KitchenBoardService(KitchenBoard board) {
        this.board = board;
    }

    @Override
    public List<KitchenTicketView> tickets(Limit limit, boolean activeOnly, Optional<String> station) {
        return board.recent(limit.value(), activeOnly, station);
    }

    @Override
    public List<KitchenTicketView> changedSince(Instant since, Optional<String> station) {
        return board.changedSince(since, station);
    }

    @Override
    public Instant now() {
        return board.now();
    }

    /** A single conditional UPDATE: two screens bumping the same ticket cannot both win. */
    @Override
    public KitchenTicketView advance(UUID ticketId, KitchenTicketStatus target) {
        KitchenTicketView ticket = board.find(ticketId).orElseThrow(() -> new TicketNotFoundException(ticketId));
        KitchenTicketStatus from = KitchenTicketStatus.valueOf(ticket.status());
        from.requireTransitionTo(target);
        if (!board.transition(ticketId, from, target)) {
            throw new TicketStateConflictException("Ticket " + ticket.orderNumber() + " was changed by another screen");
        }
        return board.find(ticketId).orElseThrow(() -> new TicketNotFoundException(ticketId));
    }
}
