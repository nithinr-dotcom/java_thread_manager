package com.fleet.storefront.adapter.in.web;

import com.fleet.storefront.adapter.in.web.security.TerminalPrincipal;
import com.fleet.storefront.application.port.in.BranchInfoQuery;
import com.fleet.storefront.application.port.in.ManageTerminalsUseCase;
import com.fleet.storefront.application.port.in.ManageTerminalsUseCase.RegisterTerminalCommand;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.terminal.Role;
import com.fleet.storefront.domain.terminal.Terminal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** The calling device's identity ({@code /me}) and the device registry. */
@RestController
@RequestMapping("/api/v1")
class TerminalController {

    private final ManageTerminalsUseCase terminals;
    private final BranchInfoQuery branch;

    TerminalController(ManageTerminalsUseCase terminals, BranchInfoQuery branch) {
        this.terminals = terminals;
        this.branch = branch;
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal TerminalPrincipal device) {
        BranchInfoQuery.BranchInfo info = branch.branch();
        return new MeResponse(device.terminalId().value(), device.name(), device.role().name(),
                new MeResponse.Branch(info.code(), info.currency().getCurrencyCode(), info.zone().getId()));
    }

    @GetMapping("/terminals")
    List<TerminalResponse> terminals() {
        return terminals.terminals().stream().map(TerminalResponse::from).toList();
    }

    @PostMapping("/terminals")
    @ResponseStatus(HttpStatus.CREATED)
    RegisteredResponse register(@Valid @RequestBody RegisterRequest request) {
        RegisterTerminalCommand command = Requests.parse(() ->
                new RegisterTerminalCommand(new TerminalId(request.terminalId()), request.name(), request.role()));
        ManageTerminalsUseCase.RegisteredTerminal registered = terminals.register(command);
        Terminal t = registered.terminal();
        return new RegisteredResponse(t.terminalId().value(), t.name(), t.role().name(), t.active(), t.createdAt(),
                t.revokedAt(), registered.apiKey());
    }

    @PostMapping("/terminals/{terminalId}/revoke")
    TerminalResponse revoke(@AuthenticationPrincipal TerminalPrincipal device, @PathVariable String terminalId) {
        TerminalId target = Requests.parse(() -> new TerminalId(terminalId));
        return TerminalResponse.from(terminals.revoke(target, device.terminalId()));
    }

    record MeResponse(String terminalId, String name, String role, Branch branch) {
        record Branch(String code, String currency, String zone) {
        }
    }

    record TerminalResponse(String terminalId, String name, String role, boolean active, Instant createdAt,
                            Instant revokedAt) {
        static TerminalResponse from(Terminal t) {
            return new TerminalResponse(t.terminalId().value(), t.name(), t.role().name(), t.active(), t.createdAt(),
                    t.revokedAt());
        }
    }

    record RegisteredResponse(String terminalId, String name, String role, boolean active, Instant createdAt,
                              Instant revokedAt, String apiKey) {
    }

    record RegisterRequest(@NotBlank @Size(max = 64) String terminalId, @NotBlank @Size(max = 100) String name,
                           @NotNull Role role) {
    }
}
