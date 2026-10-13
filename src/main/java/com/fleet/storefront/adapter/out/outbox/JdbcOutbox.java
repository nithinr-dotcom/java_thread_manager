package com.fleet.storefront.adapter.out.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.storefront.application.port.out.Outbox;
import com.fleet.storefront.domain.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Appends to {@code outbox_event} on the caller's connection, i.e. inside the checkout transaction. */
@Repository
class JdbcOutbox implements Outbox {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcOutbox(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(Message message) {
        String payload;
        try {
            payload = json.writeValueAsString(message.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Outbox payload not serializable: " + message.eventType(), e);
        }
        jdbc.sql("""
                        INSERT INTO outbox_event (id, destination, event_type, aggregate_id, payload)
                        VALUES (:id, :destination, :type, :aggregateId, CAST(:payload AS jsonb))
                        """)
                .param("id", UuidV7.next())
                .param("destination", message.destination().name())
                .param("type", message.eventType())
                .param("aggregateId", message.aggregateId())
                .param("payload", payload)
                .update();
    }
}
