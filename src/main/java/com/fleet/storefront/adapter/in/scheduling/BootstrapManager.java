package com.fleet.storefront.adapter.in.scheduling;

import com.fleet.storefront.application.port.in.ManageTerminalsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * First start of a fresh node: registers a manager device for the key in
 * {@code STOREFRONT_BOOTSTRAP_MANAGER_KEY} so an operator can sign in to the back office and register
 * the real devices. Does nothing once any manager exists; remove the variable after provisioning.
 */
@Component
class BootstrapManager implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapManager.class);

    private final ManageTerminalsUseCase terminals;
    private final String key;

    BootstrapManager(ManageTerminalsUseCase terminals,
                     @Value("${storefront.security.bootstrap-manager-key:}") String key) {
        this.terminals = terminals;
        this.key = key;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (terminals.bootstrapManager(key)) {
            log.warn("Registered device 'manager-bootstrap' from the bootstrap key. Register named manager "
                    + "devices, revoke 'manager-bootstrap' and remove STOREFRONT_BOOTSTRAP_MANAGER_KEY.");
        }
    }
}
