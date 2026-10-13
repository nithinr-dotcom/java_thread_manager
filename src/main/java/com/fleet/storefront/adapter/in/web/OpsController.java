package com.fleet.storefront.adapter.in.web;

import com.fleet.storefront.application.port.in.Limit;
import com.fleet.storefront.application.port.in.OutboxStatusQuery;
import com.fleet.storefront.application.readmodel.DeadLetterView;
import com.fleet.storefront.application.readmodel.OutboxDestinationStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ops")
class OpsController {

    private final OutboxStatusQuery outbox;

    OpsController(OutboxStatusQuery outbox) {
        this.outbox = outbox;
    }

    @GetMapping("/outbox")
    OutboxStatusResponse outbox() {
        return new OutboxStatusResponse(outbox.outboxStatus().stream().map(Destination::from).toList());
    }

    @GetMapping("/outbox/dead")
    List<DeadLetterView> deadLetters(@RequestParam(defaultValue = "50") int limit) {
        Limit max = Requests.parse(() -> new Limit(limit));
        return outbox.deadLetters(max);
    }

    /** Bulk re-queue, e.g. after fixing HQ credentials: {@code POST /outbox/dead/retry?destination=HQ}. */
    @PostMapping("/outbox/dead/retry")
    RequeueResponse retryAll(@RequestParam String destination) {
        return new RequeueResponse(outbox.retryDeadLetters(destination.trim().toUpperCase(Locale.ROOT)));
    }

    record RequeueResponse(int requeued) {
    }

    @PostMapping("/outbox/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retry(@PathVariable UUID id) {
        outbox.retryDeadLetter(id);
    }

    record OutboxStatusResponse(List<Destination> destinations) {
    }

    record Destination(String destination, long pending, long published, long failing, long dead,
                       Instant oldestPendingAt, String lastError) {

        static Destination from(OutboxDestinationStatus s) {
            return new Destination(s.destination(), s.pending(), s.published(), s.failing(), s.dead(),
                    s.oldestPendingAt().orElse(null), s.lastError().orElse(null));
        }
    }
}
