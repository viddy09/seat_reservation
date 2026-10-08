package com.paytmmoney.seatreservation.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;

/**
 * Strict JSON for money safety (ADR-021). Each setting is explicit even where Jackson 3's default already matches, so
 * the guarantee doesn't depend on library defaults.
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfig {

    @Bean
    JsonMapperBuilderCustomizer strictJson() {
        return builder -> builder
                // 250.5 for a long must be rejected (400), never silently truncated to 250.
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                // "250" (a string) is not a number either.
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                // Unknown fields are ignored and never bound, so a spoofed "user_id" in a body has no effect (I6).
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
