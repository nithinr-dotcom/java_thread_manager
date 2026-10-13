package com.fleet.storefront.application.port.in;

import java.util.Objects;

/** Shape rules for the idempotency key every state-changing order command carries. */
final class IdempotentCommand {

    private IdempotentCommand() {
    }

    static String requireKey(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency key must be 1..128 characters");
        }
        return idempotencyKey;
    }

    static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw new IllegalArgumentException("Reason must be 1..200 characters");
        }
        return reason.trim();
    }
}
