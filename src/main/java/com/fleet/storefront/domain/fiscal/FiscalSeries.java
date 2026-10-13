package com.fleet.storefront.domain.fiscal;

/** Independent gap-free numbering series of fiscal documents, each with its own hash chain. */
public enum FiscalSeries {
    /** Customer orders: {@code ORD-{BRANCH}-{YYYYMMDD}-{NNNN}}. */
    ORD,
    /** Refund documents: {@code REF-{BRANCH}-{YYYYMMDD}-{NNNN}}. */
    REF
}
