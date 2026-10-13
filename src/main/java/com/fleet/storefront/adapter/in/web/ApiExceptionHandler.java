package com.fleet.storefront.adapter.in.web;

import com.fleet.storefront.domain.shared.DomainException;
import com.fleet.storefront.domain.shared.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Set;

/** Maps failures to RFC 7807 problem documents carrying a stable {@code code} for terminals. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final Set<String> NOT_FOUND = Set.of(
            "ORDER_NOT_FOUND", "TICKET_NOT_FOUND", "TERMINAL_NOT_FOUND", "DEAD_LETTER_NOT_FOUND", "PAYMENT_NOT_FOUND");
    private static final Set<String> CONFLICT = Set.of(
            "OUT_OF_STOCK", "ORDER_NOT_PAYABLE", "ORDER_NOT_CANCELLABLE", "ORDER_NOT_REFUNDABLE",
            "TICKET_STATE_CONFLICT", "VERSION_CONFLICT", "PRODUCT_EXISTS", "TERMINAL_EXISTS", "CANNOT_REVOKE_SELF",
            "PAYMENT_ALREADY_VOIDED", "LAST_MANAGER");

    /** Business rule violated: 404 / 409 / 400 / 422 by stable code; extra facts become problem properties. */
    @ExceptionHandler(DomainException.class)
    ProblemDetail domainRuleViolated(DomainException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusOf(e.code()), e.getMessage());
        problem.setProperty("code", e.code());
        e.details().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidInput(InvalidRequestException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("code", ValidationException.CODE);
        return problem;
    }

    /** Lock timeout, deadlock victim, pool exhaustion: nothing was committed, the terminal may retry. */
    @ExceptionHandler({TransientDataAccessException.class, CannotGetJdbcConnectionException.class})
    ResponseEntity<ProblemDetail> busy(RuntimeException e) {
        log.warn("Request shed under load: {}", e.toString());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Store system is busy, please retry with the same Idempotency-Key");
        problem.setProperty("code", "BUSY_RETRY");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    /** Anything else is a bug: log it with the request id, never leak internals to the client. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled error (requestId={})", MDC.get(RequestIdFilter.MDC_REQUEST_ID), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error; quote the X-Request-Id when reporting it");
        problem.setProperty("code", "INTERNAL_ERROR");
        return problem;
    }

    /** Framework-detected client errors (missing header, bad JSON, failed bean validation) get the same stable code. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (statusCode.is4xxClientError()) {
                problem.setProperty("code", ValidationException.CODE);
            } else if (statusCode.is5xxServerError()) {
                problem.setProperty("code", "INTERNAL_ERROR");
            }
        }
        return response;
    }

    private static HttpStatus statusOf(String code) {
        if (NOT_FOUND.contains(code)) {
            return HttpStatus.NOT_FOUND;
        }
        if (CONFLICT.contains(code)) {
            return HttpStatus.CONFLICT;
        }
        if (ValidationException.CODE.equals(code)) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
