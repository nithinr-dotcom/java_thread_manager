package com.fleet.storefront.domain.terminal;

import com.fleet.storefront.domain.shared.Sha256;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Device API keys: {@code sf_} + 256 random bits (base64url). Keys have full entropy, so a plain
 * SHA-256 is a sufficient one-way hash (no salt/KDF needed, unlike passwords) and allows an indexed
 * lookup by hash.
 */
public final class ApiKeys {

    public static final int MAX_LENGTH = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "sf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String apiKey) {
        return Sha256.hex(apiKey);
    }
}
