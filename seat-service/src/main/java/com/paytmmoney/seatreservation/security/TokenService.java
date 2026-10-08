package com.paytmmoney.seatreservation.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Issues HS256 tokens. Identity in every later request comes only from a token this service signed (I6). */
public final class TokenService {

    private final JwtEncoder encoder;
    private final Duration ttl;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, Duration ttl, Clock clock) {
        this.encoder = encoder;
        this.ttl = ttl;
        this.clock = clock;
    }

    public IssuedToken issue(String userId, Role role) {
        if (!UserIds.isValid(userId)) {
            throw new IllegalArgumentException("Invalid user id");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId)
                .claim(Role.CLAIM, role.claimValue())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String token, Instant expiresAt) {}
}
