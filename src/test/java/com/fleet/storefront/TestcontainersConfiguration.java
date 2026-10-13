package com.fleet.storefront;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** One container per JVM, shared by every Spring test context. */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withCommand("postgres", "-c", "max_connections=200");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }
}
