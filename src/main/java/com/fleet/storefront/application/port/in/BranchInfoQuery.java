package com.fleet.storefront.application.port.in;

import java.time.ZoneId;
import java.util.Currency;

/** Static facts about the store this node serves. */
public interface BranchInfoQuery {

    BranchInfo branch();

    record BranchInfo(String code, Currency currency, ZoneId zone) {
    }
}
