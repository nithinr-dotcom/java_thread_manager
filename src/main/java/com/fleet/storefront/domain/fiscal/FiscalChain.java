package com.fleet.storefront.domain.fiscal;

import com.fleet.storefront.domain.shared.Sha256;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Tamper-evident hash chain over the fiscal documents of one series, branch and business day:
 * {@code hash(n) = SHA-256(hash(n-1) + "|" + canonical(n))}, starting from {@link #GENESIS}.
 * Editing, deleting or re-ordering any issued document breaks every later link, which
 * {@code GET /reports/fiscal-chain} detects. Jurisdictions requiring certified signing devices plug a
 * signer in behind the same allocation step; this chain is the portable baseline.
 */
public final class FiscalChain {

    public static final String GENESIS = "0".repeat(64);
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private FiscalChain() {
    }

    public static String link(String previousHash, String canonicalDocument) {
        Objects.requireNonNull(canonicalDocument, "canonicalDocument");
        String previous = previousHash == null ? GENESIS : previousHash;
        if (!HASH.matcher(previous).matches()) {
            throw new IllegalArgumentException("Not a SHA-256 hex hash: " + previous);
        }
        return Sha256.hex(previous + "|" + canonicalDocument);
    }
}
