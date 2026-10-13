package com.fleet.storefront;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StorefrontApplication {

    public static void main(String[] args) {
        SpringApplication.run(StorefrontApplication.class, args);
    }
}
