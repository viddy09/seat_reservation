package com.paytmmoney.seatreservation.api;

import com.paytmmoney.seatreservation.config.AppProperties;
import com.paytmmoney.seatreservation.domain.InvalidAdminKeyException;
import com.paytmmoney.seatreservation.domain.InvalidTokenRequestException;
import com.paytmmoney.seatreservation.security.Role;
import com.paytmmoney.seatreservation.security.TokenService;
import com.paytmmoney.seatreservation.security.TokenService.IssuedToken;
import com.paytmmoney.seatreservation.security.UserIds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development token issuer, standing in for a real identity provider. Only registered when
 * {@code ALLOW_DEV_TOKENS=true}; otherwise the route does not exist (404). Admin tokens additionally require the
 * {@code X-Admin-Key} header to match {@code ADMIN_API_KEY} (ADR-022).
 */
@RestController
@ConditionalOnBooleanProperty("seatres.auth.allow-dev-tokens")
public class AuthController {

    static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    private final TokenService tokens;
    private final byte[] adminApiKey;

    public AuthController(TokenService tokens, AppProperties properties) {
        this.tokens = tokens;
        this.adminApiKey = properties.auth().adminApiKey().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/auth/token")
    public TokenResponse issue(
            @RequestBody TokenRequest request, @RequestHeader(name = ADMIN_KEY_HEADER, required = false) String key) {
        if (!UserIds.isValid(request.userId())) {
            throw new InvalidTokenRequestException("user_id must match " + UserIds.FORMAT.pattern());
        }
        Role role = request.role() == null
                ? Role.USER
                : Role.fromClaim(request.role())
                        .orElseThrow(() -> new InvalidTokenRequestException("role must be 'user' or 'admin'"));
        if (role == Role.ADMIN && !adminKeyMatches(key)) {
            throw new InvalidAdminKeyException();
        }
        IssuedToken issued = tokens.issue(request.userId(), role);
        return new TokenResponse(issued.token(), issued.expiresAt());
    }

    /** Constant-time comparison; an unset ADMIN_API_KEY never matches. */
    private boolean adminKeyMatches(String presented) {
        return adminApiKey.length > 0
                && presented != null
                && MessageDigest.isEqual(adminApiKey, presented.getBytes(StandardCharsets.UTF_8));
    }

    public record TokenRequest(String userId, String role) {}

    public record TokenResponse(String token, Instant expiresAt) {}
}
