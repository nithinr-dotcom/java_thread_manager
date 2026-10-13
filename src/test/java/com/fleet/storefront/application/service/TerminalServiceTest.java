package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.ManageTerminalsUseCase.RegisterTerminalCommand;
import com.fleet.storefront.application.port.in.ManageTerminalsUseCase.RegisteredTerminal;
import com.fleet.storefront.application.port.in.TerminalNotFoundException;
import com.fleet.storefront.application.port.out.TerminalRegistry;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.terminal.CannotRevokeSelfException;
import com.fleet.storefront.domain.terminal.LastManagerException;
import com.fleet.storefront.domain.terminal.Role;
import com.fleet.storefront.domain.terminal.Terminal;
import com.fleet.storefront.domain.terminal.TerminalExistsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TerminalServiceTest {

    private final FakeRegistry registry = new FakeRegistry();
    private final InMemoryStorefront tx = new InMemoryStorefront();
    private final TerminalService service = new TerminalService(registry, tx,
            Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void registeringReturnsAKeyOnce_thatAuthenticatesUntilRevoked() {
        RegisteredTerminal pos = service.register(new RegisterTerminalCommand(new TerminalId("pos-1"), "Front", Role.POS));

        assertThat(registry.hashes).doesNotContainKey(pos.apiKey());
        assertThat(service.authenticate(pos.apiKey())).map(Terminal::terminalId).contains(new TerminalId("pos-1"));
        assertThat(service.authenticate("sf_wrong")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();

        service.revoke(new TerminalId("pos-1"), new TerminalId("mgr-1"));
        assertThat(service.authenticate(pos.apiKey())).isEmpty();
    }

    @Test
    void terminalIdsAreUnique_andADeviceCannotRevokeItself() {
        service.register(new RegisterTerminalCommand(new TerminalId("pos-1"), "Front", Role.POS));
        assertThatThrownBy(() -> service.register(new RegisterTerminalCommand(new TerminalId("pos-1"), "Again", Role.POS)))
                .isInstanceOf(TerminalExistsException.class);
        assertThatThrownBy(() -> service.revoke(new TerminalId("pos-1"), new TerminalId("pos-1")))
                .isInstanceOf(CannotRevokeSelfException.class);
        assertThatThrownBy(() -> service.revoke(new TerminalId("nope"), new TerminalId("mgr")))
                .isInstanceOf(TerminalNotFoundException.class);
    }

    @Test
    void theLastActiveManagerCannotBeRevoked() {
        service.register(new RegisterTerminalCommand(new TerminalId("mgr-1"), "Office", Role.MANAGER));
        service.register(new RegisterTerminalCommand(new TerminalId("mgr-2"), "Office 2", Role.MANAGER));

        service.revoke(new TerminalId("mgr-2"), new TerminalId("mgr-1"));
        assertThatThrownBy(() -> service.revoke(new TerminalId("mgr-1"), new TerminalId("pos-x")))
                .isInstanceOf(LastManagerException.class);
    }

    @Test
    void bootstrapRegistersAManagerOnlyOnAFreshNode() {
        String key = "sf_bootstrap_key_that_is_long_enough_0123";
        assertThat(service.bootstrapManager("")).isFalse();
        assertThat(service.bootstrapManager(key)).isTrue();
        assertThat(service.authenticate(key)).map(Terminal::role).contains(Role.MANAGER);
        assertThat(service.bootstrapManager(key)).isFalse();
        assertThat(service.bootstrapManager(key + "-other")).isFalse();
        assertThatThrownBy(() -> service.bootstrapManager("short")).isInstanceOf(IllegalArgumentException.class);
    }

    static final class FakeRegistry implements TerminalRegistry {
        final Map<String, Terminal> hashes = new HashMap<>();
        final List<Terminal> all = new ArrayList<>();

        @Override
        public Optional<Terminal> findByKeyHash(String apiKeyHash) {
            return Optional.ofNullable(hashes.get(apiKeyHash))
                    .flatMap(t -> findByTerminalId(t.terminalId()));
        }

        @Override
        public Optional<Terminal> findByTerminalId(TerminalId terminalId) {
            return all.stream().filter(t -> t.terminalId().equals(terminalId)).findFirst();
        }

        @Override
        public List<Terminal> findAll() {
            return List.copyOf(all);
        }

        @Override
        public void insert(Terminal terminal, String apiKeyHash) {
            hashes.put(apiKeyHash, terminal);
            all.add(terminal);
        }

        @Override
        public Optional<Terminal> revoke(TerminalId terminalId, Instant at) {
            return findByTerminalId(terminalId).map(t -> {
                Terminal revoked = new Terminal(t.id(), t.terminalId(), t.name(), t.role(), t.createdAt(), at);
                all.replaceAll(x -> x.terminalId().equals(terminalId) ? revoked : x);
                return revoked;
            });
        }

        @Override
        public long countActiveForUpdate(Role role) {
            return countActive(role);
        }

        @Override
        public long countActive(Role role) {
            return all.stream().filter(t -> t.role() == role && t.active()).count();
        }
    }
}
