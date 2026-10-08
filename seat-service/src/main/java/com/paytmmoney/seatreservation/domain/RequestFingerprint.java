package com.paytmmoney.seatreservation.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * {@code sha256(show_id + "\n" + sorted seats joined by ",")}. Seats are sorted before hashing, so
 * {@code ["A13","A12"]} and {@code ["A12","A13"]} are the same request. A replay with the same key must match this
 * fingerprint; otherwise the key is being reused for a different request (409).
 */
public final class RequestFingerprint {

    private final byte[] sha256;

    private RequestFingerprint(byte[] sha256) {
        this.sha256 = sha256;
    }

    public static RequestFingerprint of(UUID showId, List<String> seats) {
        String canonical =
                showId + "\n" + String.join(",", seats.stream().sorted().toList());
        try {
            return new RequestFingerprint(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    public static RequestFingerprint fromBytes(byte[] sha256) {
        return new RequestFingerprint(sha256.clone());
    }

    public byte[] bytes() {
        return sha256.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RequestFingerprint that && MessageDigest.isEqual(sha256, that.sha256);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(sha256);
    }
}
