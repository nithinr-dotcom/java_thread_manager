package com.fleet.storefront.adapter.in.web;

import com.fleet.storefront.domain.shared.UuidV7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Correlation: every request gets an {@code X-Request-Id} (the caller's, if well-formed, else a new
 * UUIDv7), echoed on the response and put in the logging MDC together with the idempotency key, so a
 * terminal's retry storm can be traced end to end. The terminal id is added by the authentication filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_IDEMPOTENCY_KEY = "idempotencyKey";
    public static final String MDC_TERMINAL = "terminalId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UuidV7.next().toString();
        String key = request.getHeader("Idempotency-Key");
        MDC.put(MDC_REQUEST_ID, requestId);
        if (key != null && SAFE.matcher(key).matches()) {
            MDC.put(MDC_IDEMPOTENCY_KEY, key);
        }
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_IDEMPOTENCY_KEY);
            MDC.remove(MDC_TERMINAL);
        }
    }
}
