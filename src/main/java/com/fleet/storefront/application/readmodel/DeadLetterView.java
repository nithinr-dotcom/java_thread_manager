package com.fleet.storefront.application.readmodel;

import java.time.Instant;
import java.util.UUID;

public record DeadLetterView(UUID id, String destination, String eventType, UUID aggregateId, int attempts,
                             String lastError, Instant createdAt, Instant deadAt) {
}
