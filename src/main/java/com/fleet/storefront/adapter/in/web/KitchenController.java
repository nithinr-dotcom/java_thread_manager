package com.fleet.storefront.adapter.in.web;

import com.fleet.storefront.application.port.in.AdvanceKitchenTicketUseCase;
import com.fleet.storefront.application.port.in.KitchenBoardQuery;
import com.fleet.storefront.application.port.in.Limit;
import com.fleet.storefront.application.readmodel.KitchenTicketView;
import com.fleet.storefront.domain.kitchen.KitchenTicketStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Kitchen Display System: ticket board, station workflow and a live stream of changes. */
@RestController
@RequestMapping("/api/v1/kitchen")
class KitchenController {

    private final KitchenBoardQuery board;
    private final AdvanceKitchenTicketUseCase advance;
    private final KitchenStreams streams;

    KitchenController(KitchenBoardQuery board, AdvanceKitchenTicketUseCase advance, KitchenStreams streams) {
        this.board = board;
        this.advance = advance;
        this.streams = streams;
    }

    @GetMapping("/tickets")
    List<KitchenTicketView> tickets(@RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(defaultValue = "false") boolean active,
                                    @RequestParam(required = false) String station) {
        Limit max = Requests.parse(() -> new Limit(limit));
        return board.tickets(max, active, station(station));
    }

    @PostMapping("/tickets/{id}/status")
    KitchenTicketView advance(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return advance.advance(id, request.status());
    }

    /** Server-sent events: snapshot of active tickets, then every creation/change (docs/API.md). */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@RequestParam(required = false) String station) {
        return streams.open(station(station));
    }

    private static Optional<String> station(String station) {
        return Optional.ofNullable(station).filter(s -> !s.isBlank()).map(s -> s.trim().toUpperCase(Locale.ROOT));
    }

    record StatusRequest(@NotNull KitchenTicketStatus status) {
    }
}
