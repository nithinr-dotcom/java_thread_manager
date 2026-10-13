package com.fleet.storefront.adapter.in.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;

/** Writes RFC 7807 bodies from the security filter chain (outside Spring MVC's exception handling). */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static void write(HttpServletResponse response, ObjectMapper json, HttpStatus status, String code, String detail)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        json.writeValue(response.getOutputStream(), problem);
    }
}
