package com.fleet.storefront.adapter.in.web.security;

import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.terminal.Role;

/** The authenticated device behind a request (inject with {@code @AuthenticationPrincipal}). */
public record TerminalPrincipal(TerminalId terminalId, String name, Role role) {
}
