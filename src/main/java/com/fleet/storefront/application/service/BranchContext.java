package com.fleet.storefront.application.service;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Objects;

/** Static facts about the store this node serves. */
public record BranchContext(String branchCode, Currency currency, ZoneId zone) {

    public BranchContext {
        Objects.requireNonNull(branchCode, "branchCode");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(zone, "zone");
    }
}
