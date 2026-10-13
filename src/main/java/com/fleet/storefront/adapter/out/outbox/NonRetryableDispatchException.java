package com.fleet.storefront.adapter.out.outbox;

/**
 * Thrown by a dispatcher when retrying cannot help (unreadable payload, unknown event type, a
 * downstream 4xx). The relay dead-letters the event immediately instead of retrying it.
 */
public class NonRetryableDispatchException extends RuntimeException {

    public NonRetryableDispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
