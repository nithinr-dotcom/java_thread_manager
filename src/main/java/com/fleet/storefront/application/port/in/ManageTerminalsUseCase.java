package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.terminal.Role;
import com.fleet.storefront.domain.terminal.Terminal;

import java.util.List;
import java.util.Objects;

/** Device registry for managers: register (issues a key once), list, revoke. */
public interface ManageTerminalsUseCase {

    List<Terminal> terminals();

    RegisteredTerminal register(RegisterTerminalCommand command);

    Terminal revoke(TerminalId target, TerminalId actor);

    /**
     * Ensures a first manager exists on a fresh node: registers {@code manager-bootstrap} with the given
     * key unless that key is already registered or another active manager exists.
     *
     * @return whether a device was registered
     */
    boolean bootstrapManager(String apiKey);

    record RegisterTerminalCommand(TerminalId terminalId, String name, Role role) {
        public RegisterTerminalCommand {
            Objects.requireNonNull(terminalId, "terminalId");
            Objects.requireNonNull(role, "role");
            if (name == null || name.isBlank() || name.length() > 100) {
                throw new IllegalArgumentException("Name must be 1..100 characters");
            }
        }
    }

    /** @param apiKey the plain key, returned exactly once */
    record RegisteredTerminal(Terminal terminal, String apiKey) {
    }
}
