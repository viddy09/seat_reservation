package com.paytmmoney.seatreservation.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The only way application code learns who the caller is: the {@code sub} of the verified JWT in the security
 * context. Request bodies are never consulted, so a spoofed {@code user_id} field has no effect (I6).
 */
@Component
public class CurrentUser {

    public String id() {
        // Unreachable for protected routes: the security filter chain rejects unauthenticated requests first.
        return idIfAuthenticated()
                .orElseThrow(() -> new IllegalStateException("No authenticated user in the security context"));
    }

    /** For logging context on routes where authentication is optional. */
    public Optional<String> idIfAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && UserIds.isValid(jwt.getName())) {
            return Optional.of(jwt.getName());
        }
        return Optional.empty();
    }
}
