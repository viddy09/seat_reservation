package com.paytmmoney.seatreservation.security;

import java.util.Optional;

/** Value of the JWT {@code role} claim. */
public enum Role {
    USER("user"),
    ADMIN("admin");

    public static final String CLAIM = "role";

    private final String claimValue;

    Role(String claimValue) {
        this.claimValue = claimValue;
    }

    public String claimValue() {
        return claimValue;
    }

    /** Spring Security authority, e.g. {@code ROLE_ADMIN}, matched by {@code hasRole("ADMIN")}. */
    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<Role> fromClaim(String value) {
        for (Role role : values()) {
            if (role.claimValue.equals(value)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
