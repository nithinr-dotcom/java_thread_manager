package com.fleet.storefront.application.port.out;

import java.util.function.Supplier;

/** Runs work atomically; any exception thrown by {@code work} rolls everything back. */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);
}
