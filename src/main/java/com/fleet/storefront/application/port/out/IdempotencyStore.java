package com.fleet.storefront.application.port.out;

import com.fleet.storefront.domain.order.TerminalId;

import java.time.Instant;
import java.util.UUID;

/**
 * Remembers which (terminal, idempotency key) pairs produced which resource (order, payment, refund).
 *
 * <p>{@link #claim} must be the first statement of the transaction and must <em>block</em> while
 * another in-flight transaction holds the same key, so that duplicates arriving within milliseconds of
 * each other serialize behind the original and then observe its outcome.
 */
public interface IdempotencyStore {

    Claim claim(TerminalId terminalId, String key, String requestFingerprint, Instant now);

    /** Records the id of the resource the claimed key produced. */
    void complete(TerminalId terminalId, String key, UUID resourceId);

    sealed interface Claim permits Claimed, AlreadyCompleted {
    }

    /** The key is new: this transaction owns it. */
    record Claimed() implements Claim {
    }

    /** The key was already used by a committed transaction. */
    record AlreadyCompleted(UUID resourceId, String requestFingerprint) implements Claim {
    }
}
