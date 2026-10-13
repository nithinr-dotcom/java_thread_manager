package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.AuthenticateTerminalQuery;
import com.fleet.storefront.application.port.in.ManageTerminalsUseCase;
import com.fleet.storefront.application.port.in.TerminalNotFoundException;
import com.fleet.storefront.application.port.out.TerminalRegistry;
import com.fleet.storefront.application.port.out.TransactionRunner;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.shared.UuidV7;
import com.fleet.storefront.domain.terminal.ApiKeys;
import com.fleet.storefront.domain.terminal.CannotRevokeSelfException;
import com.fleet.storefront.domain.terminal.LastManagerException;
import com.fleet.storefront.domain.terminal.Role;
import com.fleet.storefront.domain.terminal.Terminal;
import com.fleet.storefront.domain.terminal.TerminalExistsException;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** Device credentials: only key hashes are stored; a plain key is returned once, at registration. */
public final class TerminalService implements AuthenticateTerminalQuery, ManageTerminalsUseCase {

    static final TerminalId BOOTSTRAP_ID = new TerminalId("manager-bootstrap");

    private final TerminalRegistry registry;
    private final TransactionRunner transactions;
    private final Clock clock;

    public TerminalService(TerminalRegistry registry, TransactionRunner transactions, Clock clock) {
        this.registry = registry;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public Optional<Terminal> authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > ApiKeys.MAX_LENGTH) {
            return Optional.empty();
        }
        return registry.findByKeyHash(ApiKeys.hash(apiKey)).filter(Terminal::active);
    }

    @Override
    public List<Terminal> terminals() {
        return registry.findAll();
    }

    @Override
    public RegisteredTerminal register(RegisterTerminalCommand command) {
        return transactions.inTransaction(() -> {
            if (registry.findByTerminalId(command.terminalId()).isPresent()) {
                throw new TerminalExistsException(command.terminalId());
            }
            String apiKey = ApiKeys.generate();
            Terminal terminal = new Terminal(UuidV7.next(), command.terminalId(), command.name().trim(), command.role(),
                    clock.instant(), null);
            registry.insert(terminal, ApiKeys.hash(apiKey));
            return new RegisteredTerminal(terminal, apiKey);
        });
    }

    @Override
    public Terminal revoke(TerminalId target, TerminalId actor) {
        if (target.equals(actor)) {
            throw new CannotRevokeSelfException();
        }
        return transactions.inTransaction(() -> {
            Terminal current = registry.findByTerminalId(target).orElseThrow(() -> new TerminalNotFoundException(target));
            if (current.role() == Role.MANAGER && current.active() && registry.countActiveForUpdate(Role.MANAGER) <= 1) {
                throw new LastManagerException();
            }
            return registry.revoke(target, clock.instant()).orElseThrow(() -> new TerminalNotFoundException(target));
        });
    }

    @Override
    public boolean bootstrapManager(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        if (apiKey.length() < 32 || apiKey.length() > ApiKeys.MAX_LENGTH) {
            throw new IllegalArgumentException("Bootstrap manager key must be 32.." + ApiKeys.MAX_LENGTH + " characters");
        }
        return transactions.inTransaction(() -> {
            if (registry.findByKeyHash(ApiKeys.hash(apiKey)).isPresent()
                    || registry.findByTerminalId(BOOTSTRAP_ID).isPresent()
                    || registry.countActive(Role.MANAGER) > 0) {
                return false;
            }
            registry.insert(new Terminal(UuidV7.next(), BOOTSTRAP_ID, "Bootstrap manager", Role.MANAGER,
                    clock.instant(), null), ApiKeys.hash(apiKey));
            return true;
        });
    }
}
