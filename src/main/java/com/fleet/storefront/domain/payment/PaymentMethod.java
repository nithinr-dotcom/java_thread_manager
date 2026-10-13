package com.fleet.storefront.domain.payment;

/**
 * Tender types. Card payments are authorised on a standalone (semi-integrated) payment terminal; the
 * node records the approval reference, never card data, which keeps it out of PCI scope.
 */
public enum PaymentMethod {
    CASH, CARD
}
