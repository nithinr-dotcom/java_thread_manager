package com.fleet.storefront.application.port.out;

import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.terminal.Role;
import com.fleet.storefront.domain.terminal.Terminal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Registered devices and the hashes of their API keys. */
public interface TerminalRegistry {

    Optional<Terminal> findByKeyHash(String apiKeyHash);

    Optional<Terminal> findByTerminalId(TerminalId terminalId);

    List<Terminal> findAll();

    void insert(Terminal terminal, String apiKeyHash);

    /** Marks the device revoked (idempotent); empty if unknown. */
    Optional<Terminal> revoke(TerminalId terminalId, Instant at);

    long countActive(Role role);

    /** Counts active devices of {@code role}, locking them until commit (serializes concurrent revocations). */
    long countActiveForUpdate(Role role);
}
