package com.fleet.storefront.domain.terminal;

/** What a registered device may do. */
public enum Role {
    /** Cashier POS, self-order kiosk, handheld: checkout, payments, cancelling unpaid orders. */
    POS,
    /** Kitchen display: tickets and their workflow. */
    KITCHEN,
    /** Back office: everything, including refunds, catalog, stock, devices, reports and operations. */
    MANAGER,
    /** Central HQ sync client: inbound catalog sync only. */
    HQ,
    /** Monitoring agent: may scrape metrics, nothing else. */
    MONITOR
}
