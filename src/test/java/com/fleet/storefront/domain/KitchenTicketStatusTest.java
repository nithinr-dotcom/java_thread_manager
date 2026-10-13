package com.fleet.storefront.domain;

import com.fleet.storefront.domain.kitchen.KitchenTicketStatus;
import com.fleet.storefront.domain.kitchen.TicketStateConflictException;
import org.junit.jupiter.api.Test;

import static com.fleet.storefront.domain.kitchen.KitchenTicketStatus.CANCELLED;
import static com.fleet.storefront.domain.kitchen.KitchenTicketStatus.IN_PROGRESS;
import static com.fleet.storefront.domain.kitchen.KitchenTicketStatus.QUEUED;
import static com.fleet.storefront.domain.kitchen.KitchenTicketStatus.READY;
import static com.fleet.storefront.domain.kitchen.KitchenTicketStatus.SERVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KitchenTicketStatusTest {

    @Test
    void stationsMoveTicketsForwardWithSkipAheadAndRecall() {
        assertThat(QUEUED.canMoveTo(IN_PROGRESS)).isTrue();
        assertThat(QUEUED.canMoveTo(READY)).isTrue();
        assertThat(IN_PROGRESS.canMoveTo(READY)).isTrue();
        assertThat(READY.canMoveTo(SERVED)).isTrue();
        assertThat(READY.canMoveTo(IN_PROGRESS)).isTrue();
    }

    @Test
    void finishedTicketsAndCancellationAreNotStationMoves() {
        for (KitchenTicketStatus target : KitchenTicketStatus.values()) {
            assertThat(SERVED.canMoveTo(target)).isFalse();
            assertThat(CANCELLED.canMoveTo(target)).isFalse();
            assertThat(QUEUED.canMoveTo(CANCELLED) || IN_PROGRESS.canMoveTo(CANCELLED)).isFalse();
        }
        assertThat(QUEUED.canMoveTo(SERVED)).isFalse();
        assertThatThrownBy(() -> SERVED.requireTransitionTo(QUEUED)).isInstanceOf(TicketStateConflictException.class);
    }

    @Test
    void activeMeansStillOnTheBoard() {
        assertThat(QUEUED.isActive() && IN_PROGRESS.isActive() && READY.isActive()).isTrue();
        assertThat(SERVED.isActive() || CANCELLED.isActive()).isFalse();
    }
}
