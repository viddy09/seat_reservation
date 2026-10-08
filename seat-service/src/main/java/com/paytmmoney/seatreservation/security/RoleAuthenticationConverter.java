package com.paytmmoney.seatreservation.security;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Maps a verified JWT to an authentication whose name is {@code sub} and whose authority comes from {@code role}. */
public final class RoleAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        // The decoder's validators already guarantee a well-formed sub and a known role.
        Role role = Role.fromClaim(jwt.getClaimAsString(Role.CLAIM)).orElseThrow();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role.authority())), jwt.getSubject());
    }
}
