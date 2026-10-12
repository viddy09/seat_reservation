package com.paytmmoney.seatreservation.api;

import com.paytmmoney.seatreservation.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Adds {@code user_id}, {@code show_id} and {@code reservation_id} to the logging context once they are known (after
 * authentication and route matching), so every log line of the request, including its access line, carries them.
 * {@link RequestIdFilter} clears the MDC when the request ends.
 */
@Component
public class LogContextInterceptor implements HandlerInterceptor {

    static final String USER_ID = "user_id";
    static final String SHOW_ID = "show_id";
    static final String RESERVATION_ID = "reservation_id";

    private final CurrentUser currentUser;

    public LogContextInterceptor(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        currentUser.idIfAuthenticated().ifPresent(id -> MDC.put(USER_ID, id));
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables) {
            putIfPresent(variables, "showId", SHOW_ID);
            putIfPresent(variables, "reservationId", RESERVATION_ID);
        }
        return true;
    }

    private static void putIfPresent(Map<?, ?> variables, String variable, String key) {
        Object value = variables.get(variable);
        if (value != null) {
            MDC.put(key, value.toString());
        }
    }
}
