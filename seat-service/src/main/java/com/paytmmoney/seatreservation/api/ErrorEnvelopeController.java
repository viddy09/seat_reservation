package com.paytmmoney.seatreservation.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's default {@code /error} page so that errors raised outside Spring MVC (in a servlet filter, or
 * via {@code response.sendError}) still use the standard envelope.
 */
@RestController
public class ErrorEnvelopeController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ErrorEnvelopeController.class);

    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        HttpStatusCode status = statusOf(request);
        if (status.is5xxServerError()) {
            log.error(
                    "Unhandled error outside MVC on {}",
                    request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
                    (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
        }
        ErrorResponse envelope = ErrorResponse.of(
                ErrorCodes.forStatus(status),
                ErrorCodes.messageFor(status),
                Map.of(),
                RequestIdFilter.current(request));
        return ResponseEntity.status(status).body(envelope);
    }

    private static HttpStatusCode statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return code instanceof Integer value
                ? HttpStatusCode.valueOf(value)
                : HttpStatusCode.valueOf(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }
}
