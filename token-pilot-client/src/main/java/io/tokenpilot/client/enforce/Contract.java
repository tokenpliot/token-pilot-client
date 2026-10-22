package io.tokenpilot.client.enforce;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import io.tokenpilot.client.UsageEvent;

/** Field rules of the Control Plane contract ({@code control-plane-v1.yaml}) shared by the enforce types. */
final class Contract {

    static final String SCHEMA_VERSION = "2026-10-01";

    static final int MAX_IDENTIFIER_LENGTH = 128;

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:-]+$");
    private static final Pattern METADATA_KEY = Pattern.compile("^[a-z][a-z0-9_.-]{0,63}$");
    private static final int USD_SCALE = 6;
    private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private Contract() {
    }

    static boolean isIdentifier(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_IDENTIFIER_LENGTH
            && IDENTIFIER.matcher(value).matches();
    }

    static String requireIdentifier(String field, String value) {
        if (!isIdentifier(value)) {
            throw new IllegalArgumentException(field + " must match " + IDENTIFIER.pattern() + " and be 1 to "
                + MAX_IDENTIFIER_LENGTH + " characters");
        }
        return value;
    }

    static String optionalIdentifier(String field, String value) {
        return value == null ? null : requireIdentifier(field, value);
    }

    static long requireTokens(String field, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    /**
     * Normalizes a USD amount to the contract's {@code UsdAmount} (at most 6 decimals). Finer amounts are rounded
     * UP: for a budget check or a charge, over-estimating by less than a micro-dollar is the safe side.
     */
    static BigDecimal usd(String field, BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        BigDecimal normalized = value.scale() > USD_SCALE ? value.setScale(USD_SCALE, RoundingMode.UP) : value;
        if (normalized.compareTo(UsageEvent.MAX_USD) > 0) {
            throw new IllegalArgumentException(field + " must be at most " + UsageEvent.MAX_USD.toPlainString());
        }
        return normalized;
    }

    /** {@code UsdAmount} wire form: plain notation, no trailing zeros, {@code "0"} for zero. */
    static String usdString(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }

    /** Same rules as {@link UsageEvent}: raw-text keys are dropped before anything leaves the process. */
    static Map<String, String> metadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, String> kept = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "metadata key must not be null");
            if (UsageEvent.FORBIDDEN_METADATA_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!METADATA_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("metadata key must match " + METADATA_KEY.pattern());
            }
            String value = Objects.requireNonNull(entry.getValue(), "metadata value must not be null");
            if (value.length() > UsageEvent.MAX_METADATA_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                    "metadata value must be at most " + UsageEvent.MAX_METADATA_VALUE_LENGTH + " characters");
            }
            kept.put(key, value);
        }
        if (kept.size() > UsageEvent.MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException(
                "metadata must have at most " + UsageEvent.MAX_METADATA_ENTRIES + " entries");
        }
        return Collections.unmodifiableMap(kept);
    }

    /**
     * Deterministic {@code Idempotency-Key}: the same logical write always gets the same key, so a retry or a
     * re-executed command replays the server's original result. Values that would fall outside the header's
     * 8..128 length are hashed instead.
     */
    static String idempotencyKey(String prefix, String value) {
        String key = prefix + value;
        if (key.length() >= MIN_IDEMPOTENCY_KEY_LENGTH && key.length() <= MAX_IDEMPOTENCY_KEY_LENGTH) {
            return key;
        }
        return prefix + sha256Hex(value);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            // Every Java platform must provide SHA-256.
            throw new IllegalStateException(exception);
        }
    }
}
