package com.fleet.storefront.domain.terminal;

import com.fleet.storefront.domain.order.TerminalId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A registered device. Its credential is an API key of which only the SHA-256 is stored. */
public record Terminal(UUID id, TerminalId terminalId, String name, Role role, Instant createdAt, Instant revokedAt) {

    public Terminal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("Device name must be 1..100 characters");
        }
    }

    public boolean active() {
        return revokedAt == null;
    }
}
