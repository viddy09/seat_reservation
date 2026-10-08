package com.paytmmoney.seatreservation.security;

import java.util.regex.Pattern;

/** Format of a user id (the JWT {@code sub}): short and log-safe. */
public final class UserIds {

    public static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_.@-]{1,64}$");

    private UserIds() {}

    public static boolean isValid(String userId) {
        return userId != null && FORMAT.matcher(userId).matches();
    }
}
