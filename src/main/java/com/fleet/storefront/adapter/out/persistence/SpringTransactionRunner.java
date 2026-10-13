package com.fleet.storefront.adapter.out.persistence;

import com.fleet.storefront.application.port.out.TransactionRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * READ COMMITTED transaction with a per-transaction {@code lock_timeout}: under a contention spike a
 * checkout waits a bounded time for a row lock and then fails with a retryable error, instead of
 * pinning a pooled connection and a request thread indefinitely.
 */
@Component
class SpringTransactionRunner implements TransactionRunner {

    private final TransactionTemplate template;
    private final JdbcClient jdbc;
    private final String lockTimeout;

    SpringTransactionRunner(PlatformTransactionManager transactionManager, JdbcClient jdbc,
                            @Value("${storefront.checkout.lock-timeout}") Duration lockTimeout) {
        this.template = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.lockTimeout = lockTimeout.toMillis() + "ms";
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> {
            jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)").param("timeout", lockTimeout).query().singleValue();
            return work.get();
        });
    }
}
