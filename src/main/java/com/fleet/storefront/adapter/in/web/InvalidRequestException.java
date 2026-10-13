package com.fleet.storefront.adapter.in.web;

/** Client input rejected while turning a request into a command (→ {@code 400 INVALID_REQUEST}). */
class InvalidRequestException extends RuntimeException {

    InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
