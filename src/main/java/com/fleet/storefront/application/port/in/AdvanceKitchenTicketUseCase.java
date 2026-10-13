package com.fleet.storefront.application.port.in;

import com.fleet.storefront.application.readmodel.KitchenTicketView;
import com.fleet.storefront.domain.kitchen.KitchenTicketStatus;

import java.util.UUID;

/** A station moves a ticket along its workflow (start, ready, served, recall). */
public interface AdvanceKitchenTicketUseCase {

    KitchenTicketView advance(UUID ticketId, KitchenTicketStatus target);
}
