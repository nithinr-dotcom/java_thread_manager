package com.fleet.storefront;

import org.springframework.boot.SpringApplication;

/** {@code ./mvnw spring-boot:test-run}: runs the node against a throwaway PostgreSQL with the demo catalog. */
public class TestStorefrontApplication {

    public static void main(String[] args) {
        SpringApplication.from(StorefrontApplication::main)
                .with(TestcontainersConfiguration.class)
                .withAdditionalProfiles("demo")
                .run(args);
    }
}
