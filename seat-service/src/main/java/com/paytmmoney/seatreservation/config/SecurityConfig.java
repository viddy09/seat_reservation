package com.paytmmoney.seatreservation.config;

import com.paytmmoney.seatreservation.api.SecurityErrorHandler;
import com.paytmmoney.seatreservation.security.Role;
import com.paytmmoney.seatreservation.security.RoleAuthenticationConverter;
import com.paytmmoney.seatreservation.security.TokenService;
import com.paytmmoney.seatreservation.security.UserIds;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT resource server. Only the routes that need identity are protected; everything else is public, so an
 * unknown path is an honest 404 rather than a 401.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorHandler errors) {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, "/shows")
                        .hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/shows/*/reserve", "/reservations/*/cancel")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/reservations/*")
                        .authenticated()
                        .anyRequest()
                        .permitAll())
                .oauth2ResourceServer(
                        oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(new RoleAuthenticationConverter()))
                                .authenticationEntryPoint(errors)
                                .accessDeniedHandler(errors))
                .exceptionHandling(
                        handling -> handling.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(AppProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Beyond signature + exp/nbf: a token must carry a well-formed subject and a known role, or it is rejected
        // with 401 before any application code runs.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, UserIds::isValid),
                new JwtClaimValidator<String>(
                        Role.CLAIM, value -> Role.fromClaim(value).isPresent())));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(AppProperties properties) {
        return NimbusJwtEncoder.withSecretKey(signingKey(properties))
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    TokenService tokenService(JwtEncoder encoder, AppProperties properties) {
        return new TokenService(encoder, properties.auth().tokenTtl(), Clock.systemUTC());
    }

    private static SecretKey signingKey(AppProperties properties) {
        return new SecretKeySpec(properties.auth().jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
