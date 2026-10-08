package com.paytmmoney.seatreservation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paytmmoney.seatreservation.security.Role;
import com.paytmmoney.seatreservation.security.RoleAuthenticationConverter;
import com.paytmmoney.seatreservation.security.TokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

/** JWT verification: good token accepted; bad signature, expiry, unknown role and bad subject rejected. */
class JwtVerificationTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef-xyz";

    private final SecurityConfig config = new SecurityConfig();
    private final AppProperties properties = properties(SECRET);
    private final JwtDecoder decoder = config.jwtDecoder(properties);
    private final JwtEncoder encoder = config.jwtEncoder(properties);

    @Test
    void validToken_mapsSubjectAndRole() {
        String token = new TokenService(encoder, Duration.ofHours(1), Clock.systemUTC())
                .issue("alice", Role.ADMIN)
                .token();

        Jwt jwt = decoder.decode(token);
        var authentication = new RoleAuthenticationConverter().convert(jwt);

        assertThat(authentication.getName()).isEqualTo("alice");
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
    }

    @Test
    void tokenSignedWithAnotherKey_isRejected() {
        JwtEncoder otherEncoder = config.jwtEncoder(properties("another-secret-0123456789abcdef-xyz"));
        String forged = new TokenService(otherEncoder, Duration.ofHours(1), Clock.systemUTC())
                .issue("alice", Role.ADMIN)
                .token();

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredToken_isRejected() {
        Clock twoHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        String expired = new TokenService(encoder, Duration.ofHours(1), twoHoursAgo)
                .issue("alice", Role.USER)
                .token();

        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    void unknownRoleOrBadSubject_isRejected() {
        assertThatThrownBy(() -> decoder.decode(sign("alice", "root"))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign("bad subject", "user"))).isInstanceOf(JwtException.class);
    }

    private String sign(String subject, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .claim(Role.CLAIM, role)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private static AppProperties properties(String secret) {
        return new AppProperties(
                new AppProperties.Db(20),
                new AppProperties.Observability(2, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new AppProperties.Auth(secret, "admin-key", true, Duration.ofHours(1)));
    }
}
