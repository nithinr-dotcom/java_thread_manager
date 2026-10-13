package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.catalog.Product;

import java.util.List;

/** Inbound catalog sync from Central HQ (push or pull), last-writer-wins on {@code version}. */
public interface SyncCatalogUseCase {

    /** Applies HQ's products atomically; rejects the whole batch on a currency mismatch. */
    SyncResult apply(List<Product> products);

    /** Pulls changes since the last applied HQ version; a no-op while offline. */
    SyncResult pullFromHq();

    record SyncResult(int applied, int skipped) {
    }
}
