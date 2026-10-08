package com.paytmmoney.seatreservation.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter in the chain: accepts a caller's {@code X-Request-ID} or generates one, exposes it to logs (MDC), to the
 * error envelope (request attribute) and to the caller (response header).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";
    public static final String MDC_KEY = "request_id";
    static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    /** Accept only short, log-safe ids so a caller cannot inject newlines or huge values into our logs. */
    private static final Pattern ACCEPTED = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Virtual threads are not pooled, but Tomcat's error dispatch and any future platform-thread executor
            // would otherwise inherit stale context.
            MDC.clear();
        }
    }

    /** Error dispatches (/error) must keep the original id, so the filter runs for them too. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    static String resolve(String incoming) {
        return incoming != null && ACCEPTED.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
    }

    static String current(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String id ? id : MDC.get(MDC_KEY);
    }
}
