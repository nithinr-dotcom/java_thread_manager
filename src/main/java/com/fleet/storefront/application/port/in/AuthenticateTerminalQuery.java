package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.terminal.Terminal;

import java.util.Optional;

/** Resolves a bearer API key to an active registered device. */
public interface AuthenticateTerminalQuery {

    Optional<Terminal> authenticate(String apiKey);
}
