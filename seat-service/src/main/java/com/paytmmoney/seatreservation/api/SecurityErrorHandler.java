package com.paytmmoney.seatreservation.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security rejects requests before Spring MVC sees them, so 401/403 can't go through the
 * {@code @RestControllerAdvice}. This writes the same envelope directly.
 */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    /** RFC 6750: tell the client which scheme is expected. */
    private static final String BEARER_CHALLENGE = "Bearer";

    private final JsonMapper json;

    public SecurityErrorHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setHeader("WWW-Authenticate", BEARER_CHALLENGE);
        write(request, response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "A valid bearer token is required");
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "Insufficient role for this operation");
    }

    private void write(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        ErrorResponse envelope = ErrorResponses.of(request, code, message, Map.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(json.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8));
    }
}
