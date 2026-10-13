package com.fleet.storefront.adapter.in.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.storefront.adapter.in.web.RequestIdFilter;
import com.fleet.storefront.application.port.in.AuthenticateTerminalQuery;
import com.fleet.storefront.domain.terminal.Terminal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code Authorization: Bearer <api key>} → the registered, non-revoked device and its role. A request
 * without credentials continues anonymously (and is rejected by the authorization rules where needed);
 * a request with bad credentials is rejected here. If the legacy {@code X-Terminal-Id} header is sent, it
 * must name the authenticated device: a terminal can no longer act under another terminal's identity.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String TERMINAL_HEADER = "X-Terminal-Id";
    private static final String BEARER = "Bearer ";

    private final AuthenticateTerminalQuery authenticate;
    private final ObjectMapper json;

    ApiKeyAuthenticationFilter(AuthenticateTerminalQuery authenticate, ObjectMapper json) {
        this.authenticate = authenticate;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<Terminal> terminal;
        try {
            terminal = header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                    ? authenticate.authenticate(header.substring(BEARER.length()).trim())
                    : Optional.empty();
        } catch (TransientDataAccessException | CannotGetJdbcConnectionException e) {
            // Outside Spring MVC: map overload here, or it would surface as an opaque 500.
            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
            ProblemResponses.write(response, json, HttpStatus.SERVICE_UNAVAILABLE, "BUSY_RETRY",
                    "Store system is busy, please retry with the same Idempotency-Key");
            return;
        }
        if (terminal.isEmpty()) {
            ProblemResponses.write(response, json, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Unknown or revoked API key");
            return;
        }
        Terminal device = terminal.get();
        String claimed = request.getHeader(TERMINAL_HEADER);
        if (claimed != null && !claimed.equals(device.terminalId().value())) {
            ProblemResponses.write(response, json, HttpStatus.FORBIDDEN, "TERMINAL_MISMATCH",
                    "X-Terminal-Id does not match the authenticated device " + device.terminalId());
            return;
        }
        TerminalPrincipal principal = new TerminalPrincipal(device.terminalId(), device.name(), device.role());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + device.role().name()))));
        SecurityContextHolder.setContext(context);
        MDC.put(RequestIdFilter.MDC_TERMINAL, device.terminalId().value());
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
